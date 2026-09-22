import type { Authorization } from "@keyneom/sync-kit/core";
import {
  createSharedBackupController,
  IndexedDbSharedBackupRegistry,
  type SharedBackupController,
} from "@keyneom/sync-kit/sharing/controller";
import { GoogleDriveSharedBackupTransport } from "@keyneom/sync-kit/stores/google-drive/sharing";
import {
  fingerprint,
  mergeVaults,
  RemoteUnavailableError,
  type RemoteRevision,
  type RemoteVaultStore,
  type VaultState,
} from "@keyweb/vault-core";
import { authorizeGoogle } from "../googleAuth";
import type { SharingIdentityLike } from "./identity";

/**
 * A shared keyring's Drive file, and the operations that change who can read it.
 *
 * The vault's own backup and a shared keyring are encrypted quite differently,
 * and the difference is the whole point. The vault is sealed with a symmetric
 * key derived from one person's passkey: there is no way to let a second person
 * in without handing over the key to everything. A shared keyring is sealed
 * with a content key that is *wrapped separately for each participant's public
 * key*, so adding a person adds one small wrapped copy and grants exactly that
 * keyring.
 *
 * All of that is sync-kit's shared-backup envelope, which Keyweb uses rather
 * than reimplements. What Keyweb supplies is the payload — a `VaultState`, the
 * same structure the vault holds — and the merge, so a shared keyring is a CRDT
 * in exactly the way the rest of the vault is and two people editing at once
 * keeps both edits.
 */

export const KEYWEB_APP_ID = "keyweb";

/** The folder shared keyrings live in, visible in the owner's Drive. */
const SHARED_FOLDER_NAME = "Keyweb shared keyrings";

/**
 * The payload codec.
 *
 * `merge` is what makes a shared keyring behave like the rest of the vault
 * rather than like a file two people overwrite in turn.
 */
const codec = {
  serialize: (value: VaultState) => value as unknown,
  parse: (value: unknown) => value as VaultState,
  merge: mergeVaults,
  fingerprint,
};

export type SharingController = SharedBackupController<VaultState>;

export function createKeywebSharingController(identity: SharingIdentityLike): SharingController {
  const authorizationProvider = {
    authorize: (): Promise<Authorization> => authorizeGoogle(),
    // The page-wide token is shared with the backup and the Picker, so this
    // one feature must not be able to sign the whole app out.
    clear: () => undefined,
  };
  return createSharedBackupController<VaultState>({
    appId: KEYWEB_APP_ID,
    codec,
    identity: () => identity.getOrCreate(),
    transport: new GoogleDriveSharedBackupTransport({
      appId: KEYWEB_APP_ID,
      authorizationProvider,
      folderName: SHARED_FOLDER_NAME,
    }),
    // Which Drive file each dataset is, and which revision was last verified.
    // Persisted, because re-deriving it means listing a folder the recipient
    // of a share cannot list at all.
    registry: new IndexedDbSharedBackupRegistry({ databaseName: "keyweb-shared" }),
  });
}

/**
 * One shared keyring, as something `VaultSync` can publish to.
 *
 * The sync engine already knows how to keep a document and a remote in step;
 * it needs no idea that this particular remote is a file two people can write
 * to, because the merge it would do anyway is the merge that makes that safe.
 *
 * Failures come back as `RemoteUnavailableError` so a shared keyring that
 * cannot be reached leaves the rest of the vault backing up normally. That
 * matters more here than for the vault: a revoked grant, a deleted file or a
 * person who left are all ordinary states for a shared keyring, and none of
 * them should stop somebody's own passwords being saved.
 */
export class SharedKeyringRemote implements RemoteVaultStore {
  readonly #controller: SharingController;
  readonly #datasetId: string;
  /** Set once the dataset is known to exist, to skip the adopt attempt. */
  #known = false;

  constructor(controller: SharingController, datasetId: string) {
    this.#controller = controller;
    this.#datasetId = datasetId;
  }

  async read(): Promise<RemoteRevision | null> {
    try {
      const result = await this.#load();
      this.#known = true;
      return { state: result.value, version: result.revisionId };
    } catch (cause) {
      // A dataset this device has a binding for but no registry record is the
      // normal state right after joining on a second device; adopting it is
      // what turns the binding into something readable.
      if (isMissing(cause)) return null;
      throw unavailable(cause);
    }
  }

  async write(state: VaultState): Promise<string> {
    try {
      const result = await this.#controller.syncDataset(this.#datasetId, {
        read: () => state,
        // Nothing to commit here: `VaultSync` owns the local document and
        // writes the merged state itself once this returns.
        apply: (merged) => merged,
      });
      this.#known = true;
      return result.revisionId;
    } catch (cause) {
      throw unavailable(cause);
    }
  }

  async #load() {
    if (this.#known) return this.#controller.loadDataset(this.#datasetId);
    try {
      return await this.#controller.loadDataset(this.#datasetId);
    } catch (cause) {
      if (!isMissing(cause)) throw cause;
      // Trust-on-first-use against the envelope's own owner key. Deliberately
      // not `requireOwned`: the common case for adopting is a keyring somebody
      // else owns and shared with us.
      return await this.#controller.adoptDataset(this.#datasetId);
    }
  }
}

/** The one file that says which keyrings are yours. Same id on every device. */
export const INDEX_DATASET_ID = "keyweb-index";

/**
 * The vault's own root: which keyrings exist and which file each lives in.
 *
 * A file of the same kind as every keyring — encrypted once, its key wrapped to
 * you — so there is nothing left that is sealed twice, and no second copy for a
 * device without the printed code to leave behind. The CRDT above it does not
 * change at all; only the place the root document is published does.
 *
 * ## Moving off the old file
 *
 * Until some device has published this index, the account's root lives in the
 * old double-sealed vault file. So a read finds the index first and, only if
 * there is none yet, reads the old file once — whatever that device can open of
 * it — so its contents are merged into this one rather than left behind. Every
 * write goes to the index, and the old file is never written again: it stays
 * exactly as it was, a fallback nobody has to maintain.
 *
 * The first device to run this publishes the index; every other device then
 * finds it and never needs the old file, or the code that opened its second
 * copy, at all.
 */
export class IndexRemote implements RemoteVaultStore {
  readonly #controller: SharingController;
  readonly #legacy: RemoteVaultStore | null;
  #exists: boolean | null = null;

  constructor(controller: SharingController, legacy: RemoteVaultStore | null) {
    this.#controller = controller;
    this.#legacy = legacy;
  }

  async read(): Promise<RemoteRevision | null> {
    const index = await this.#readIndex();
    if (index) return index;
    if (!this.#legacy) return null;

    /*
     * No index anywhere yet: make it from the old file, here, on the read.
     *
     * Not on the write that follows, because there may not be one. Handing the
     * old file's contents back as "the remote" meant a device already holding
     * all of them saw nothing to publish — the remote apparently had it all —
     * and the index was never created. Every device would then go on reading
     * the old file for ever, which is the thing being moved off.
     *
     * The old file is only ever read. A device that cannot open it — the
     * newer copy sealed to a key it lacks — refuses here rather than seeding
     * the index from a copy it knows is stale; the device that can open it
     * makes the index, and this one finds it next time.
     */
    const legacy = await this.#legacy.read();
    if (!legacy) return null;
    try {
      const created = await this.#controller.createDataset(INDEX_DATASET_ID, legacy.state);
      this.#exists = true;
      return { state: created.value, version: created.revisionId };
    } catch (cause) {
      throw unavailable(cause);
    }
  }

  async write(state: VaultState): Promise<string> {
    try {
      if ((await this.#knownToExist()) === false) {
        const created = await this.#controller.createDataset(INDEX_DATASET_ID, state);
        this.#exists = true;
        return created.revisionId;
      }
      const result = await this.#controller.syncDataset(INDEX_DATASET_ID, {
        read: () => state,
        apply: (merged) => merged,
      });
      return result.revisionId;
    } catch (cause) {
      throw unavailable(cause);
    }
  }

  async #readIndex(): Promise<RemoteRevision | null> {
    try {
      const result = await this.#controller.loadDataset(INDEX_DATASET_ID);
      this.#exists = true;
      return { state: result.value, version: result.revisionId };
    } catch (cause) {
      if (!isMissing(cause)) throw unavailable(cause);
    }
    try {
      // Owned, not merely readable: the index is yours or it is not the index.
      const adopted = await this.#controller.adoptDataset(INDEX_DATASET_ID, {
        requireOwned: true,
      });
      this.#exists = true;
      return { state: adopted.value, version: adopted.revisionId };
    } catch (cause) {
      if (!isMissing(cause)) throw unavailable(cause);
      this.#exists = false;
      return null;
    }
  }

  async #knownToExist(): Promise<boolean> {
    if (this.#exists === null) await this.#readIndex();
    return this.#exists === true;
  }
}


/**
 * Is this "we have never established what this dataset is"?
 *
 * Two different answers mean it. `not-found` is the file: nothing in the
 * registry and nothing in the folder listing. `state` with this message is the
 * registry alone — sync-kit knows the file but has no pinned owner key for it,
 * and will not read something whose signature it cannot check.
 *
 * The pin is per device and is established by adopting, so every device of
 * yours *except* the one that shared the keyring meets this the first time it
 * sees that dataset. Only `not-found` was recognised, so that ordinary state
 * came out of the sync as "backup had a problem" with sync-kit's sentence
 * about verified invitations attached — on a keyring the person had shared
 * from their own phone and simply could not open on their laptop.
 *
 * Matched on the message as well as the code because `state` covers more than
 * this condition, and adopting past all of them would turn "this file is
 * wrong" into "read it anyway", which is the opposite of what a pin is for.
 */
function isMissing(cause: unknown): boolean {
  const code = (cause as { code?: unknown } | null)?.code;
  if (code === "not-found") return true;
  if (code !== "state") return false;
  const message = cause instanceof Error ? cause.message : "";
  return message.includes("no pinned owner key");
}

function unavailable(cause: unknown): RemoteUnavailableError {
  if (cause instanceof RemoteUnavailableError) return cause;
  const message = cause instanceof Error && cause.message ? cause.message : "";
  return new RemoteUnavailableError(
    message || "Keyweb couldn't reach that shared keyring.",
  );
}
