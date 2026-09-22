import { GoogleDriveFileStore } from "@keyneom/sync-kit/stores/google-drive";
import { listAccessibleSyncKitDatasets } from "@keyneom/sync-kit/stores/google-drive/sharing";
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
   * Optional, because a device can have no passkey to seal one with.
   *
   * Both platforms derive this key now, from one passkey, so the ordinary file
   * has both members. But a phone whose passkey ceremony was declined or is
   * unavailable still writes `{ v, recovery }` and carries any passkey
   * envelope forward untouched — so code that assumes this member is present
   * is code that has only ever run against a file some browser made.
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

/**
 * This Google account holds more than one Keyweb vault file.
 *
 * Refused rather than resolved. Picking one means writing to it, and writing
 * to the wrong one strands everything in the other — so the only safe move is
 * to stop and let somebody look at their own Drive, where the files are
 * visible and dated.
 */
export class TooManyBackupsError extends BackupUnreadableError {
  constructor(readonly count: number) {
    super(
      `There are ${count} Keyweb backup files in this Google account, and Keyweb won't ` +
        "guess which one is yours. Open Google Drive, look in the Keyweb folder, and remove " +
        "or rename the ones you don't want — the newest is usually the one to keep.",
    );
    this.name = "TooManyBackupsError";
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
  return parsed as BackupPayload;
}

function parsePayloadSafe(content: string): BackupPayload | null {
  try {
    return parsePayload(content);
  } catch {
    return null;
  }
}


/**
 * What is in a Google account, as far as can be told without a key.
 *
 * Deliberately says nothing about contents. Names of shared keyrings come from
 * Drive file names, which their owner chose and Drive already shows them; the
 * vault's own contents stay sealed.
 */
export type AccountContents = {
  backup: {
    /** When the vault last changed, from whichever copy is newer. */
    updatedAt: string | null;
    opensWithPasskey: boolean;
    opensWithCode: boolean;
  } | null;
  sharedKeyrings: string[];
};

/** One candidate vault file, described without opening it. */
export type BackupFile = {
  fileId: string;
  name: string;
  /** When the vault inside it last changed, from the newer of its copies. */
  updatedAt: string | null;
  hasPasskeyCopy: boolean;
  hasCodeCopy: boolean;
  chosen: boolean;
  /**
   * How big the sealed file is.
   *
   * The nearest thing to "what is in it" that can be had without a key. An
   * empty vault seals to about a kilobyte whatever it was sealed with, and a
   * vault with a few hundred passwords is tens of kilobytes, so somebody
   * deciding which of two files is theirs can tell a stub from the real one
   * without opening either.
   */
  bytes: number;
  /** Whether this is the file the app is actually reading and writing. */
  inUse: boolean;
};

const CHOSEN_KEY = "keyweb.backup-file";

/**
 * The file this browser was told to use, when the account holds several.
 *
 * Per browser rather than in the vault, because it is a statement about which
 * of two parallel vaults is the real one — and storing it *inside* one of them
 * would mean the answer is only readable once the question is already settled.
 */
export function chosenBackupFile(): string | null {
  try {
    return localStorage.getItem(CHOSEN_KEY);
  } catch {
    return null;
  }
}

export function chooseBackupFile(fileId: string): void {
  try {
    localStorage.setItem(CHOSEN_KEY, fileId);
  } catch {
    // A browser that cannot remember it will ask again, which is tolerable.
  }
}

function safeParse(content: string): BackupPayload | null {
  try {
    return parsePayload(content);
  } catch {
    return null;
  }
}

export type DriveRemoteOptions = {
  clientId: string;
  cipher: VaultCipher;
  /** Seals the second, recovery-code-openable copy. */
  recoveryCipher?: VaultCipher;
  /** Injectable for tests; defaults to a real Drive-backed store. */
  store?: GoogleDriveFileStore;
  authorize?: () => Promise<Authorization>;
  /** The clock both copies of a write are stamped from. Injectable for tests. */
  now?: () => string;
};

export class GoogleDriveRemote implements RemoteVaultStore {
  readonly #store: GoogleDriveFileStore;
  readonly #cipher: VaultCipher;
  #recoveryCipher: VaultCipher | null;
  readonly #authorize: () => Promise<Authorization>;
  readonly #now: () => string;
  #fileId: string | null = null;
  #folderId: string | null = null;

  constructor(options: DriveRemoteOptions) {
    this.#store = options.store ?? new GoogleDriveFileStore();
    this.#cipher = options.cipher;
    this.#recoveryCipher = options.recoveryCipher ?? null;
    // The page-wide authorizer, so the backup does not open a second popup
    // after the Picker or the sharing identity already opened one.
    this.#authorize = options.authorize ?? (() => authorizeGoogle(options.clientId));
    this.#now = options.now ?? (() => new Date().toISOString());
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

    /*
     * More than one vault file is a question for the person, not a guess.
     *
     * This took `files.find(name matches) ?? files[0]` — an arbitrary choice
     * from a list Drive returns in no promised order — so two devices could
     * land on two different files and each be perfectly consistent: both sync,
     * both succeed, both report themselves backed up, and they hold different
     * vaults. No error anywhere, because from inside either one nothing is
     * wrong.
     *
     * A vault file is not a keyring. It is the *whole vault* sealed as one
     * blob, so a second one is a parallel vault rather than more of this one,
     * and there is no merging them. Somebody has to say which is theirs.
     *
     * So: a choice already made is honoured, and otherwise this stops and the
     * screen lists them. Stopping is the safe half; the list is the useful
     * half, and the first version of this had only the safe one.
     */
    if (found.files.length > 1) {
      const chosen = chosenBackupFile();
      const match = chosen && found.files.find((file) => file.fileId === chosen);
      if (!match) throw new TooManyBackupsError(found.files.length);
      this.#fileId = match.fileId;
      return this.#fileId;
    }

    this.#fileId = found.files[0]?.fileId ?? null;
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

  /**
   * What this Google account actually holds, without opening any of it.
   *
   * Every field here is readable from file metadata and envelope headers, so
   * it answers the question somebody asks when a screen comes up empty —
   * *is my data gone?* — without a key to anything.
   *
   * The empty screen was the real complaint. A browser that cannot open the
   * backup showed nothing and said it was synced, which is indistinguishable
   * from an account with nothing in it. Those are opposite situations and a
   * person cannot be expected to tell them apart by feel.
   */
  async describeContents(): Promise<AccountContents> {
    const authorization = await this.#auth();
    const shared = await listAccessibleSyncKitDatasets({
      appId: "keyweb",
      authorization,
    }).catch(() => []);

    const fileId = await this.#findFile(authorization);
    if (!fileId) return { backup: null, sharedKeyrings: shared.map((d) => d.name) };

    const content = await this.#store.readText(fileId, authorization);
    const payload = content.trim() ? parsePayload(content) : null;
    if (!payload) return { backup: null, sharedKeyrings: shared.map((d) => d.name) };

    return {
      backup: {
        // The newer of the two, because that is when the vault last changed —
        // whichever device happened to write that copy.
        updatedAt:
          [sealedAt(payload.passkey), sealedAt(payload.recovery)]
            .filter((at): at is string => at !== null)
            .sort()
            .pop() ?? null,
        opensWithPasskey: payload.passkey !== undefined,
        opensWithCode: payload.recovery !== undefined,
      },
      sharedKeyrings: shared.map((dataset) => dataset.name),
    };
  }

  /**
   * The same one-line read-out the phone gives, for comparing them.
   *
   * The question it answers is "are these two devices even looking at the same
   * file", which is invisible from either side alone and decides everything
   * else. A file id and two timestamps; nothing secret.
   */
  async describeFile(): Promise<string> {
    const authorization = await this.#auth();
    const fileId = await this.#findFile(authorization);
    if (!fileId) return "No Keyweb backup file in this Google account.";

    const found = await this.#store.list(authorization, { appProperties: VAULT_MARKER });
    const content = await this.#store.readText(fileId, authorization);
    const payload = content.trim() ? parsePayload(content) : null;
    if (!payload) return `File ${fileId.slice(-8)} is empty.`;

    const copies = [
      `passkey copy ${payload.passkey === undefined ? "none" : (sealedAt(payload.passkey) ?? "yes")}`,
      `code copy ${payload.recovery === undefined ? "none" : (sealedAt(payload.recovery) ?? "yes")}`,
    ];
    const many = found.files.length > 1 ? ` (${found.files.length} vault files!)` : "";
    return `file ${fileId.slice(-8)}${many} · ${copies.join(" · ")}`;
  }

  /**
   * Every vault file in this account, for somebody to choose between.
   *
   * Each row carries what can be known without a key — when Drive last saw it
   * change, which copies it holds and when each was sealed — because the
   * choice is "which of these is mine", and a list of identical file names
   * answers nothing.
   */
  async listBackupFiles(): Promise<BackupFile[]> {
    const authorization = await this.#auth();
    const found = await this.#store.list(authorization, { appProperties: VAULT_MARKER });

    return Promise.all(
      found.files.map(async (file) => {
        const content = await this.#store.readText(file.fileId, authorization).catch(() => "");
        const payload = content.trim() ? safeParse(content) : null;
        return {
          fileId: file.fileId,
          name: file.name,
          updatedAt:
            [sealedAt(payload?.passkey), sealedAt(payload?.recovery)]
              .filter((at): at is string => at !== null)
              .sort()
              .pop() ?? null,
          hasPasskeyCopy: payload?.passkey !== undefined,
          hasCodeCopy: payload?.recovery !== undefined,
          chosen: file.fileId === chosenBackupFile(),
          bytes: content.length,
          inUse: file.fileId === (chosenBackupFile() ?? this.#fileId),
        };
      }),
    );
  }

  /**
   * Throw away one of the vault files in this account.
   *
   * Only ever what somebody picked off a list that showed them its date and
   * its size, and never the file this device is using — that one is reachable
   * through "replace the backup", which writes rather than deletes, so there
   * is no path here that ends in no backup at all.
   *
   * Drive keeps deleted files in the owner's bin for thirty days, which is
   * worth saying on screen: this is recoverable by them, and by nobody else.
   */
  async deleteBackupFile(fileId: string): Promise<void> {
    if (!fileId) throw new Error("No file was chosen.");
    const inUse = chosenBackupFile() ?? this.#fileId;
    if (fileId === inUse) {
      throw new Error("That is the backup Keyweb is using. Choose the other one first.");
    }
    const authorization = await this.#auth();
    await this.#store.delete(fileId, authorization);
    if (this.#fileId === fileId) this.#fileId = null;
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
       * The newest copy this browser can actually open.
       *
       * Not "the passkey one, always". A phone that cannot reseal the passkey
       * envelope leaves it frozen, so preferring it means reading a vault the
       * phone moved on from — and reporting that as synced, which is how two
       * devices ended up confidently showing different things.
       *
       * Once this browser holds the recovery code it can open that copy too,
       * so the answer stops being an error and becomes the newer data. That is
       * what makes "enter your code" a fix rather than an acknowledgement.
       */
      const openable: { at: string | null; open: () => Promise<VaultState> }[] = [];
      if (payload.passkey !== undefined) {
        openable.push({
          at: sealedAt(payload.passkey),
          open: () => this.#cipher.openState(payload.passkey),
        });
      }
      if (payload.recovery !== undefined && this.#recoveryCipher) {
        const recoveryCipher = this.#recoveryCipher;
        openable.push({
          at: sealedAt(payload.recovery),
          open: () => recoveryCipher.openState(payload.recovery),
        });
      }
      // Newest first, and a copy with no timestamp sorts last rather than
      // winning by accident.
      openable.sort((a, b) => (b.at ?? "").localeCompare(a.at ?? ""));

      let opened: VaultState | null = null;
      let openedAt = "";
      for (const candidate of openable) {
        try {
          opened = await candidate.open();
          openedAt = candidate.at ?? "";
          break;
        } catch {
          // Try the next one. A key that does not fit this copy is ordinary.
        }
      }

      /*
       * A copy that opens but is older than one that does not is not a read.
       *
       * The phone refuses this case; this browser used to return the copy and
       * call itself synced. It is reachable in one move: a phone with no
       * passkey rewrites only the recovery copy, leaving the passkey copy
       * frozen, and a browser holding the passkey but not the code opens the
       * frozen one. It then shows a vault the phone has moved on from and
       * republishes from it.
       *
       * The timestamps are the only evidence available without the other key,
       * and they are enough to know this is not the newest. Saying so sends
       * somebody to their recovery code, which is the thing that fixes it.
       */
      const newestAt = [payload.passkey, payload.recovery]
        .filter((copy) => copy !== undefined)
        .map((copy) => sealedAt(copy) ?? "")
        .reduce((newest, at) => (at > newest ? at : newest), "");

      if (opened !== null) {
        if (newestAt !== "" && openedAt < newestAt) throw new BackupBehindError();
        return { state: opened, version };
      }

      // Nothing opened. Which message depends on whether the thing this
      // browser cannot read is merely newer, or the only copy there is.
      throw passkeyCopyIsStale(payload)
        ? new BackupBehindError()
        : new BackupNeedsRecoveryCodeError();
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

  async write(
    state: VaultState,
    expectedVersion: string | null,
    createOnly = false,
  ): Promise<string> {
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

    // Preflight before anything else. A revision that moved is a conflict
    // even when the new bytes do not parse: the engine re-reads and merges,
    // and "unreadable" would hide that the file changed.
    if (expectedVersion !== null) {
      const current = await this.#version(fileId, authorization);
      if (current !== expectedVersion) {
        throw new VersionConflictError(
          `Expected revision ${expectedVersion} but the backup is at ${current}.`,
        );
      }
    }

    const carried = await this.#carriedRecovery(fileId, authorization);
    // `#carriedRecovery` returns undefined both for a missing recovery member
    // and for a file that could not be parsed. The raw read distinguishes
    // them: an unreadable file is left untouched, except by the deliberate
    // replace action, which passes createOnly false and no expected revision.
    if (createOnly || expectedVersion !== null) {
      const raw = await this.#store.readText(fileId, authorization);
      if (raw.trim() && parsePayloadSafe(raw) === null) {
        throw new BackupUnreadableError(
          "The backup file is in Google Drive but Keyweb couldn't read it, so nothing was changed.",
        );
      }
      if (createOnly && raw.trim()) {
        throw new VersionConflictError(
          "A backup appeared after it was read. Keyweb will merge it instead of replacing it.",
        );
      }
    }

    const sealed = JSON.stringify(await this.#payload(state, carried));
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

    /*
     * One time for the whole write, not one per envelope.
     *
     * Sealing stamps the envelope with the moment of the call, so sealing the
     * same vault twice in a row wrote two copies a millisecond or two apart.
     * The phone holds no key to the passkey copy, so those stamps are the only
     * evidence it has about which copy is current — and a recovery copy older
     * than the passkey copy is exactly what a browser leaves behind when it
     * rewrites one and carries the other forward. It could not tell the two
     * situations apart, so after every write from this browser it declared the
     * backup unreadable and offered to replace it with its own.
     *
     * The phone has always done this correctly: it seals both copies with a
     * single `at`. This is that, on the side that was getting it wrong.
     */
    const at = this.#now();
    const seal = (cipher: VaultCipher, value: VaultState) =>
      cipher.sealStateAt ? cipher.sealStateAt(value, at) : cipher.sealState(value);

    const recovery = mine !== null && canReseal ? await seal(mine, state) : carried;
    return {
      v: 1,
      passkey: await seal(this.#cipher, state),
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
