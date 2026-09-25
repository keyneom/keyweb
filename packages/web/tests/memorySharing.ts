import type {
  SharedBackupEnvelopeV1,
  SharingInvitationV1,
  SharingPublicKeyResponseV1,
} from "@keyneom/sync-kit/sharing";
import {
  createSharedBackupController,
  MemorySharedBackupRegistry,
} from "@keyneom/sync-kit/sharing/controller";
import type {
  SharedBackupStorage,
  SharedBackupTransport,
  SharedDatasetDrivePermission,
  SharedDatasetFile,
  SharedDatasetHead,
  SharedDatasetPermission,
  SharedExchangeFile,
  SharedKeyResponseFile,
  VersionedSharedDataset,
} from "@keyneom/sync-kit/sharing";
import type { WebCryptoSharingIdentity } from "@keyneom/sync-kit/sharing/web-crypto";
import { fingerprint, mergeVaults, type VaultState } from "@keyweb/vault-core";
import { KEYWEB_APP_ID, type SharingController } from "../src/vault/sharing/controller";

/**
 * Drive, as far as a keyring's file goes: one shared folder in memory.
 *
 * Only the dataset half is real. Invitations and exchanges are the link flow,
 * which these tests do not use, so they refuse rather than pretend.
 */
export class MemorySharingTransport implements SharedBackupTransport {
  readonly datasets = new Map<string, VersionedSharedDataset>();
  readonly permissions = new Map<string, SharedDatasetDrivePermission[]>();
  #counter = 0;

  async ensureStorage(): Promise<SharedBackupStorage> {
    return { appFolderId: "app-folder", exchangesFolderId: "exchanges-folder" };
  }

  async listDatasets(): Promise<SharedDatasetFile[]> {
    return [...this.datasets.values()].map(({ datasetId, fileId, name }) => ({
      datasetId,
      fileId,
      name,
      canEdit: true,
    }));
  }

  async listDatasetHeads(): Promise<SharedDatasetHead[]> {
    return [...this.datasets.values()].map(({ datasetId, fileId, version }) => ({
      datasetId,
      fileId,
      version,
    }));
  }

  async readDataset(fileId: string): Promise<VersionedSharedDataset> {
    const stored = this.datasets.get(fileId);
    if (!stored) throw Object.assign(new Error(`Missing ${fileId}`), { code: "not-found" });
    return structuredClone(stored);
  }

  async createDataset(
    datasetId: string,
    envelope: SharedBackupEnvelopeV1,
  ): Promise<VersionedSharedDataset> {
    const fileId = `dataset-${datasetId}`;
    const stored = {
      datasetId,
      fileId,
      name: `${datasetId}.sync-kit.json`,
      canEdit: true,
      envelope: structuredClone(envelope),
      version: `"${++this.#counter}"`,
    };
    this.datasets.set(fileId, stored);
    return structuredClone(stored);
  }

  async writeDataset(
    current: VersionedSharedDataset,
    envelope: SharedBackupEnvelopeV1,
  ): Promise<VersionedSharedDataset> {
    const actual = this.datasets.get(current.fileId);
    if (actual?.version !== current.version) {
      throw Object.assign(new Error("Conflict"), { code: "conflict" });
    }
    const updated = { ...actual, envelope: structuredClone(envelope), version: `"${++this.#counter}"` };
    this.datasets.set(current.fileId, updated);
    return structuredClone(updated);
  }

  async setDatasetPermission(
    fileId: string,
    emailAddress: string,
    role: string,
  ): Promise<SharedDatasetPermission> {
    const permission: SharedDatasetDrivePermission = {
      permissionId: `permission-${emailAddress}`,
      role: role === "viewer" ? "reader" : "writer",
      emailAddress,
      inherited: false,
    };
    const existing = (this.permissions.get(fileId) ?? []).filter(
      (candidate) => candidate.permissionId !== permission.permissionId,
    );
    this.permissions.set(fileId, [...existing, permission]);
    return { permissionId: permission.permissionId, role: permission.role };
  }

  async removeDatasetPermission(fileId: string, permissionId: string): Promise<void> {
    this.permissions.set(
      fileId,
      (this.permissions.get(fileId) ?? []).filter((p) => p.permissionId !== permissionId),
    );
  }

  async listDatasetPermissions(fileId: string): Promise<SharedDatasetDrivePermission[]> {
    return this.permissions.get(fileId) ?? [];
  }

  async grantExchangeAccess(): Promise<{ drivePermissionId: string; appFolderId: string }> {
    throw new Error("Not used by these tests.");
  }
  async createInvitation(_invitation: SharingInvitationV1): Promise<string> {
    throw new Error("Not used by these tests.");
  }
  async createKeyResponse(_response: SharingPublicKeyResponseV1): Promise<string> {
    throw new Error("Not used by these tests.");
  }
  async listExchanges(): Promise<SharedExchangeFile[]> {
    return [];
  }
  async readInvitation(): Promise<SharingInvitationV1> {
    throw new Error("Not used by these tests.");
  }
  async readKeyResponse(): Promise<SharedKeyResponseFile> {
    throw new Error("Not used by these tests.");
  }
  async deleteExchange(): Promise<void> {}
}

/** A controller as Keyweb builds one, over the in-memory Drive. */
export function memoryController(
  identity: WebCryptoSharingIdentity,
  transport: MemorySharingTransport,
): SharingController {
  return createSharedBackupController<VaultState>({
    appId: KEYWEB_APP_ID,
    codec: {
      serialize: (value: VaultState) => value as unknown,
      parse: (value: unknown) => value as VaultState,
      merge: mergeVaults,
      fingerprint,
    },
    identity: async () => identity,
    transport,
    registry: new MemorySharedBackupRegistry(),
  });
}

/** Named values, as the vault keeps them beside itself. */
export class MemoryMeta {
  values = new Map<string, unknown>();
  async readMeta(key: string) {
    return this.values.get(key);
  }
  async writeMeta(key: string, value: unknown) {
    this.values.set(key, JSON.parse(JSON.stringify(value)) as unknown);
  }
}
