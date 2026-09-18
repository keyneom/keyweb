import { GoogleDriveFileStore } from "@keyneom/sync-kit/stores/google-drive";
import type { Authorization } from "@keyneom/sync-kit/core";
import {
  BackupUnreadableError,
  RemoteUnavailableError,
  type RemoteRevision,
  type RemoteVaultStore,
  type VaultState,
  VersionConflictError,
} from "@keyweb/vault-core";
import type { VaultCipher } from "@keyweb/vault-idb";
import { authorizeGoogle } from "./googleAuth";

/**
 * Encrypted backup in the user's own Google Drive.
 *
 * Drive only ever holds the same sealed envelope the device holds: Google
 * stores bytes it cannot read, and Keyweb has no server that could read them
 * either. What Drive does provide is durability — the copy that survives a lost
 * phone.
 *
 * The write is conditional. `drive.file` gives us a private per-app view, but
 * two of the user's own devices can still race, and a blind overwrite would
 * discard whichever revision lost. The version token comes from Drive's **v2**
 * metadata rather than an HTTP ETag: v3 rarely exposes ETags at all, and
 * browsers hide the header behind CORS, so `headRevisionId` from the v2 JSON
 * body is the only token that reliably survives a browser round trip.
 *
 * A lost race is still safe rather than merely detected. The engine re-reads,
 * re-merges through the CRDT join, and republishes, so the outcome is an extra
 * round trip rather than a lost edit.
 */

const VAULT_MARKER = { keyweb: "vault-v1" } as const;
const FOLDER_NAME = "Keyweb";
const FILE_NAME = "keyweb-vault-v1.json";
const CONTENT_TYPE = "application/json";

export { KEYWEB_SCOPES } from "./googleAuth";

/**
 * What actually sits in the Drive file.
 *
 * The same vault sealed twice: once under the passkey-derived key and once
 * under the recovery-code-derived key. Both are written in the same request,
 * so the recovery copy can never lag behind the real one -- a stale recovery
 * copy would be worse than none, because it would restore silently wrong.
 */
type BackupPayload = {
  v: 1;
  /**
   * Optional, because a vault created on a phone has never had one.
   *
   * Keyweb's Android app does not derive the passkey key — not because it
 * cannot (sync-kit-android ships `AndroidPasskeyKeyProvider`, and easy-bc
 * uses it) but because this app was built believing it could not —
   * it writes the recovery envelope and carries any passkey envelope forward
   * untouched. So a backup made entirely on a phone is `{ v, recovery }`, and
   * code that assumes this member is present is code that has only ever been
   * run against a backup a browser made.
   */
  passkey?: unknown;
  recovery?: unknown;
};

/**
 * The `updatedAt` an envelope was sealed at, or null if it is not one.
 *
 * Comparing the two envelopes' timestamps is how any device can tell whether
 * the recovery copy has fallen behind the real one *without* being able to open
 * either. Nothing else in the file can answer that: the fingerprint would, but
 * it embeds raw field values and must never leave the device.
 */
function sealedAt(envelope: unknown): string | null {
  if (!envelope || typeof envelope !== "object") return null;
  const value = (envelope as { updatedAt?: unknown }).updatedAt;
  return typeof value === "string" ? value : null;
}

/** True when the recovery copy is older than the vault it is supposed to restore. */
export function recoveryIsStale(payload: {
  passkey: unknown;
  recovery?: unknown;
}): boolean {
  const primary = sealedAt(payload.passkey);
  const recovery = sealedAt(payload.recovery);
  if (primary === null || recovery === null) return false;
  return recovery < primary;
}

/**
 * True when *this browser's* copy is the one that has fallen behind.
 *
 * The other direction, and the one that hurt. A phone that can only reseal the
 * recovery envelope leaves the passkey copy frozen at whatever a browser last
 * wrote — so the browser opens its own copy, succeeds, reports itself synced,
 * and shows a vault that is weeks out of date or, when the browser's copy was
 * written by a fresh setup, empty.
 *
 * Nothing about that looks like a failure from inside the browser. The
 * decrypt works, the file is there, the sync completes. The only evidence is
 * the timestamp on the envelope it cannot open, which is exactly what this
 * reads. Two devices each reporting success against a different copy of
 * somebody's passwords is worse than either of them erroring.
 *
 * Equal timestamps are fine and are the normal case: a device that can seal
 * both writes both in one pass, from one state, at one moment.
 */
export function passkeyCopyIsStale(payload: { passkey?: unknown; recovery?: unknown }): boolean {
  const primary = sealedAt(payload.passkey);
  const recovery = sealedAt(payload.recovery);
  if (primary === null || recovery === null) return false;
  return primary < recovery;
}

/**
 * Recognising the wrapper, and the bare envelope that predates it.
 *
 * This used to decide by asking whether a `passkey` member was present, and
 * treat its absence as "this must be the old bare-envelope format". That was
 * wrong in the one case nobody had run: a vault created on a phone. Android
 * does not derive the passkey key, so it writes `{ v, recovery }` with no
 * passkey member at all — and the old test read that whole wrapper as if it
 * *were* a bare envelope. Two things followed, both silent:
 *
 *  - restoring in a browser handed the wrapper to the envelope parser, which
 *    rejected it as "not a supported v1 encrypted snapshot" — an error about
 *    file versions for what is really "this backup has no browser key yet";
 *  - and `#carriedRecovery` read `.recovery` off that mis-parse, got
 *    `undefined`, and wrote the backup back *without* the recovery envelope —
 *    deleting the only thing the phone can open.
 *
 * So the bare form is now recognised by what it actually is, an envelope, and
 * a wrapper is a wrapper even when the member this browser wants is missing.
 */
/**
 * The backup is real and this browser has no key for it.
 *
 * Its own error rather than a null or a transport failure, because those are
 * the two readings that end in an overwrite or a silent empty screen.
 */
export class BackupNeedsRecoveryCodeError extends BackupUnreadableError {
  constructor() {
    super(
      "This backup was made on a phone, which can't create a key for this browser. " +
        "Enter your recovery code once and this browser will make its own.",
    );
    this.name = "BackupNeedsRecoveryCodeError";
  }
}

/**
 * This browser's copy of the backup is older than the one beside it.
 *
 * Its own error so the screen can say the true thing — your phone has newer
 * passwords than this browser can read — rather than either lying about being
 * up to date or claiming the backup is broken. The recovery code is the way
 * across, and after one use this browser writes both copies and stops falling
 * behind.
 */
export class BackupBehindError extends BackupUnreadableError {
  constructor() {
    super(
      "Your phone has newer passwords than this browser can read. Enter your recovery code " +
        "once to catch up — after that this browser stays in step on its own.",
    );
    this.name = "BackupBehindError";
  }
}

/** Can this cipher actually open that envelope? The only honest test is to try. */
async function opens(cipher: VaultCipher, envelope: unknown): Promise<boolean> {
  try {
    await cipher.openState(envelope);
    return true;
  } catch {
    return false;
  }
}

function parsePayload(content: string): BackupPayload | null {
  const parsed = JSON.parse(content) as BackupPayload | Record<string, unknown>;
  if (!parsed || typeof parsed !== "object") return null;
  if (looksLikeEnvelope(parsed)) {
    // A backup written before the recovery copy existed is a bare envelope.
    return { v: 1, passkey: parsed };
  }
  return parsed as BackupPayload;
}

/**
 * Enough of an envelope to tell it from the wrapper that holds two of them.
 *
 * Deliberately not a full validation: the question here is only "which of the
 * two shapes is this", and the real parser rejects a malformed envelope with a
 * better message than anything this function could invent.
 */
function looksLikeEnvelope(value: object): boolean {
  return (value as { schemaVersion?: unknown }).schemaVersion === 1;
}

export type DriveRemoteOptions = {
  clientId: string;
  cipher: VaultCipher;
  /** Seals the second, recovery-code-openable copy. */
  recoveryCipher?: VaultCipher;
  /** Injectable for tests; defaults to a real Drive-backed store. */
  store?: GoogleDriveFileStore;
  authorize?: () => Promise<Authorization>;
};

export class GoogleDriveRemote implements RemoteVaultStore {
  readonly #store: GoogleDriveFileStore;
  readonly #cipher: VaultCipher;
  #recoveryCipher: VaultCipher | null;
  readonly #authorize: () => Promise<Authorization>;
  #fileId: string | null = null;
  #folderId: string | null = null;

  constructor(options: DriveRemoteOptions) {
    this.#store = options.store ?? new GoogleDriveFileStore();
    this.#cipher = options.cipher;
    this.#recoveryCipher = options.recoveryCipher ?? null;
    // The page-wide authorizer, so the backup does not open a second popup
    // after the Picker or the sharing identity already opened one.
    this.#authorize = options.authorize ?? (() => authorizeGoogle(options.clientId));
  }

  /** Translate transport failures into the calm offline state. */
  /**
   * Start keeping the recovery copy current from now on.
   *
   * Used when a device adopts a code it did not mint. Until this is called the
   * device carries the existing recovery envelope forward untouched rather than
   * rewriting or dropping it.
   */
  setRecoveryCipher(cipher: VaultCipher): void {
    this.#recoveryCipher = cipher;
  }

  async #auth(): Promise<Authorization> {
    try {
      return await this.#authorize();
    } catch (cause) {
      throw new RemoteUnavailableError(
        cause instanceof Error && cause.message
          ? cause.message
          : "Keyweb couldn't reach your Google account.",
      );
    }
  }

  async #findFile(authorization: Authorization): Promise<string | null> {
    if (this.#fileId) return this.#fileId;
    const found = await this.#store.list(authorization, {
      appProperties: VAULT_MARKER,
    });
    const file = found.files.find((entry) => entry.name === FILE_NAME) ?? found.files[0];
    this.#fileId = file?.fileId ?? null;
    return this.#fileId;
  }

  /**
   * A version token that survives a browser round trip.
   *
   * Prefers `headRevisionId` from Drive v2's JSON body over an HTTP ETag,
   * which v3 usually omits and CORS usually hides.
   */
  async #version(fileId: string, authorization: Authorization): Promise<string> {
    const head = await this.#store.getV2WriteHead(fileId, authorization);
    return head.headRevisionId ?? head.etag;
  }

  /**
   * Fetch the backup envelope without decrypting it.
   *
   * This is what makes restoring onto a replacement phone possible. A new
   * device has no local envelope, and the key is derived from the passkey plus
   * the salt *recorded in the envelope* — so minting a fresh key locally would
   * produce a different key that can never read the backup. The envelope is
   * self-describing, so unlocking must be seeded from this.
   */
  async fetchSealedState(): Promise<unknown | null> {
    const authorization = await this.#auth();
    try {
      const fileId = await this.#findFile(authorization);
      if (!fileId) return null;
      const content = await this.#store.readText(fileId, authorization);
      return content.trim() ? (parsePayload(content)?.passkey ?? null) : null;
    } catch (cause) {
      if (cause instanceof RemoteUnavailableError) throw cause;
      throw new RemoteUnavailableError(
        cause instanceof Error ? cause.message : "Keyweb couldn't read your backup.",
      );
    }
  }

  /** The recovery-code-sealed copy, for opening a backup without the passkey. */
  async fetchRecoverySealed(): Promise<unknown | null> {
    const authorization = await this.#auth();
    try {
      const fileId = await this.#findFile(authorization);
      if (!fileId) return null;
      const content = await this.#store.readText(fileId, authorization);
      if (!content.trim()) return null;
      return parsePayload(content)?.recovery ?? null;
    } catch (cause) {
      if (cause instanceof RemoteUnavailableError) throw cause;
      throw new RemoteUnavailableError(
        cause instanceof Error ? cause.message : "Keyweb couldn't read your backup.",
      );
    }
  }

  async read(): Promise<RemoteRevision | null> {
    const authorization = await this.#auth();
    try {
      const fileId = await this.#findFile(authorization);
      if (!fileId) return null;
      const version = await this.#version(fileId, authorization);
      const content = await this.#store.readText(fileId, authorization);
      if (!content.trim()) return null;
      const payload = parsePayload(content);
      if (!payload) return null;
      /*
       * A backup with no envelope this key can open is NOT an empty backup.
       *
       * This returned null, on the reasoning that null would let the browser
       * publish and thereby add the passkey envelope it was missing. That was
       * wrong, and it cost somebody their backup: null means "there is nothing
       * here", the engine believed it, and the browser published an empty
       * vault over a phone's real one.
       *
       * The rule this violated is the same one the whole engine is built on —
       * never turn "I cannot read this" into "I may overwrite this". So it is
       * an error now, with its own name, and the screen it reaches asks for
       * the recovery code. A browser cannot bootstrap itself into a phone's
       * backup by writing to it; it has to be let in.
       */
      if (payload.passkey === undefined) {
        throw new BackupNeedsRecoveryCodeError();
      }
      /*
       * A passkey envelope this passkey cannot open is the same problem as no
       * passkey envelope at all, and needs the same answer.
       *
       * It happens whenever a browser holds a *different* credential from the
       * one that sealed the file — a second browser, a reinstall, a profile
       * that lost its passkey. Left as a raw failure it became
       * `RemoteUnavailableError`, which means "offline": the engine retries
       * quietly forever, the screen says nothing useful, and there is no way
       * out because the thing that would fix it is a recovery code nobody was
       * asked for.
       */
      /*
       * Refuse a copy that is demonstrably behind the other one.
       *
       * Opening it would succeed, and that is the problem: the browser would
       * report itself synced while showing a vault the phone has since moved
       * on from — or an empty one. A wrong answer delivered confidently is
       * worse than an error, and this is the only evidence available without
       * the key to the other envelope.
       */
      if (passkeyCopyIsStale(payload)) {
        throw new BackupBehindError();
      }

      let state: VaultState;
      try {
        state = await this.#cipher.openState(payload.passkey);
      } catch {
        throw new BackupNeedsRecoveryCodeError();
      }
      return { state, version };
    } catch (cause) {
      if (cause instanceof RemoteUnavailableError) throw cause;
      /*
       * The base class, not each sibling by name.
       *
       * This listed `BackupNeedsRecoveryCodeError` specifically, so the next
       * unreadable-backup error added — the one for a copy that has fallen
       * behind — was silently re-wrapped as a transport failure and became
       * "offline" again. Catching the family means a new member cannot be
       * quietly downgraded by a rethrow list nobody remembered to update.
       */
      if (cause instanceof BackupUnreadableError) throw cause;
      throw new RemoteUnavailableError(
        cause instanceof Error ? cause.message : "Keyweb couldn't read your backup.",
      );
    }
  }

  async write(state: VaultState, expectedVersion: string | null): Promise<string> {
    const authorization = await this.#auth();

    let fileId: string | null;
    try {
      fileId = await this.#findFile(authorization);
    } catch (cause) {
      throw new RemoteUnavailableError(
        cause instanceof Error ? cause.message : "Keyweb couldn't reach your backup.",
      );
    }

    // First publish: create the folder and the file. Nothing exists yet, so
    // there is no recovery copy to preserve.
    if (!fileId) {
      if (expectedVersion !== null) {
        // We were told to expect a revision but the file is gone. Refusing is
        // safer than recreating it, because the file may simply be invisible
        // to this session rather than actually deleted.
        throw new VersionConflictError("The backup file could not be found.");
      }
      const sealed = JSON.stringify(await this.#payload(state, undefined));
      const folderId = await this.#ensureFolder(authorization);
      const createdId = await this.#store.create(FILE_NAME, sealed, authorization, {
        parentId: folderId,
        contentType: CONTENT_TYPE,
        appProperties: VAULT_MARKER,
      });
      this.#fileId = createdId;
      return this.#version(createdId, authorization);
    }

    // Preflight: refuse if the remote moved since it was read. sync-kit
    // documents a narrow window between this check and the upload where a
    // last-writer can still win; the CRDT join is what makes that recoverable
    // rather than destructive.
    if (expectedVersion !== null) {
      const current = await this.#version(fileId, authorization);
      if (current !== expectedVersion) {
        throw new VersionConflictError(
          `Expected revision ${expectedVersion} but the backup is at ${current}.`,
        );
      }
    }

    const sealed = JSON.stringify(
      await this.#payload(state, await this.#carriedRecovery(fileId, authorization)),
    );
    await this.#store.write(fileId, sealed, authorization, { contentType: CONTENT_TYPE });
    return this.#version(fileId, authorization);
  }

  async #payload(state: VaultState, carried: unknown): Promise<BackupPayload> {
    /*
     * Reseal the recovery copy only with a code that already opens it.
     *
     * A browser holding its *own* recovery secret — minted the first time
     * somebody set Keyweb up in it — would otherwise reseal the recovery
     * envelope under that secret, silently retiring the code the phone was
     * using and locking the phone out of its own backup. That is exactly what
     * happened, and the printed sheet in somebody's drawer stops working with
     * no error anywhere.
     *
     * So the cipher has to prove itself against what is already there. If it
     * cannot, the envelope is carried forward untouched, which is what a
     * device that cannot rewrite it is supposed to do.
     */
    const mine = this.#recoveryCipher;
    const canReseal =
      mine !== null && (carried === undefined || (await opens(mine, carried)));
    const recovery = mine !== null && canReseal ? await mine.sealState(state) : carried;
    return {
      v: 1,
      passkey: await this.#cipher.sealState(state),
      ...(recovery === undefined ? {} : { recovery }),
    };
  }

  /**
   * The recovery envelope this device cannot rewrite, read back so the write
   * carries it forward instead of deleting it.
   *
   * A second device unlocks with the same passkey but has no copy of the
   * printed code, so it cannot reseal the recovery envelope. Omitting it would
   * delete the only thing that opens the backup without a passkey, and the
   * sheet in someone's filing cabinet would stop working with no indication
   * until the day they needed it. Carrying it forward leaves it stale instead,
   * which `recoveryIsStale` reports so the app can ask for the code rather than
   * let it quietly rot.
   *
   * It used to skip this read entirely when this device *could* reseal, on the
   * reasoning that a device with a recovery cipher has no need to preserve
   * anything. That reasoning has a hole in it big enough to lose a backup
   * through: holding *a* recovery code is not the same as holding *this
   * backup's* recovery code. A browser with its own code from its own first
   * run took that path and resealed the envelope under a code the phone had
   * never seen. So the read always happens, and `#payload` decides.
   */
  async #carriedRecovery(fileId: string, authorization: Authorization): Promise<unknown> {
    let existing: string;
    try {
      existing = await this.#store.readText(fileId, authorization);
    } catch (cause) {
      // Not knowing what is there must not escalate into deleting it.
      throw new RemoteUnavailableError(
        cause instanceof Error ? cause.message : "Keyweb couldn't read your backup.",
      );
    }
    if (!existing.trim()) return undefined;
    try {
      return parsePayload(existing)?.recovery;
    } catch {
      // Unreadable content holds no recovery envelope worth preserving, and
      // refusing to write would strand this device permanently.
      return undefined;
    }
  }

  async #ensureFolder(authorization: Authorization): Promise<string> {
    if (this.#folderId) return this.#folderId;
    const found = await this.#store.list(authorization, {
      appProperties: { keyweb: "folder" },
    });
    const existing = found.files[0]?.fileId;
    if (existing) {
      this.#folderId = existing;
      return existing;
    }
    const createdId = await this.#store.createFolder(FOLDER_NAME, authorization, {
      appProperties: { keyweb: "folder" },
    });
    this.#folderId = createdId;
    return createdId;
  }
}
