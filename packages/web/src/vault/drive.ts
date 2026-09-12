import { GoogleWebAuthorizationProvider } from "@keyneom/sync-kit/auth/google-web";
import {
  GOOGLE_DRIVE_APPDATA_SCOPE,
  GOOGLE_DRIVE_FILE_SCOPE,
} from "@keyneom/sync-kit/auth/google-web";
import { GoogleDriveFileStore } from "@keyneom/sync-kit/stores/google-drive";
import type { Authorization } from "@keyneom/sync-kit/core";
import {
  RemoteUnavailableError,
  type RemoteRevision,
  type RemoteVaultStore,
  type VaultState,
  VersionConflictError,
} from "@keyweb/vault-core";
import type { VaultCipher } from "@keyweb/vault-idb";

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

export const KEYWEB_SCOPES = `${GOOGLE_DRIVE_FILE_SCOPE} ${GOOGLE_DRIVE_APPDATA_SCOPE}`;

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
  passkey: unknown;
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

function parsePayload(content: string): BackupPayload | null {
  const parsed = JSON.parse(content) as BackupPayload | Record<string, unknown>;
  if (parsed && typeof parsed === "object" && "passkey" in parsed) {
    return parsed as BackupPayload;
  }
  // A backup written before the recovery copy existed is a bare envelope.
  return { v: 1, passkey: parsed };
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
    this.#authorize =
      options.authorize ??
      (() => {
        const provider = new GoogleWebAuthorizationProvider({
          clientId: options.clientId,
          scope: KEYWEB_SCOPES,
        });
        return provider.authorize();
      });
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
      const state = await this.#cipher.openState(payload.passkey);
      return { state, version };
    } catch (cause) {
      if (cause instanceof RemoteUnavailableError) throw cause;
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
    const recovery = this.#recoveryCipher
      ? await this.#recoveryCipher.sealState(state)
      : carried;
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
   * A device that *can* reseal skips the extra round trip entirely.
   */
  async #carriedRecovery(fileId: string, authorization: Authorization): Promise<unknown> {
    if (this.#recoveryCipher) return undefined;
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
