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
import type { SharingIdentity } from "./identity";

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

export function createKeywebSharingController(identity: SharingIdentity): SharingController {
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

function isMissing(cause: unknown): boolean {
  const code = (cause as { code?: unknown } | null)?.code;
  return code === "not-found";
}

function unavailable(cause: unknown): RemoteUnavailableError {
  if (cause instanceof RemoteUnavailableError) return cause;
  const message = cause instanceof Error && cause.message ? cause.message : "";
  return new RemoteUnavailableError(
    message || "Keyweb couldn't reach that shared keyring.",
  );
}
