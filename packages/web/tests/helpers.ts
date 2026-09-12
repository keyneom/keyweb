import type { GoogleDriveFileStore } from "@keyneom/sync-kit/stores/google-drive";

/**
 * A stand-in for Drive that behaves the way Drive actually behaves: files are
 * addressed by id, every write bumps a revision, and another device can land a
 * revision between our read and our write.
 */
export class FakeDrive {
  files = new Map<
    string,
    { name: string; content: string; revision: number; appProperties: Record<string, string> }
  >();
  private nextId = 1;
  offline = false;
  /** Runs between the preflight check and the upload, to inject a race. */
  onBeforeWrite: (() => Promise<void> | void) | null = null;

  private guard() {
    if (this.offline) throw new Error("network unreachable");
  }

  async list(_auth: unknown, options: { appProperties?: Record<string, string> } = {}) {
    this.guard();
    const wanted = Object.entries(options.appProperties ?? {});
    const files = [...this.files.entries()]
      .filter(([, file]) => wanted.every(([k, v]) => file.appProperties[k] === v))
      .map(([fileId, file]) => ({ fileId, name: file.name }));
    return { files };
  }

  async readText(fileId: string) {
    this.guard();
    return this.files.get(fileId)?.content ?? "";
  }

  async getV2WriteHead(fileId: string) {
    this.guard();
    const file = this.files.get(fileId);
    if (!file) throw new Error("not found");
    return { etag: `etag-${file.revision}`, headRevisionId: String(file.revision) };
  }

  async create(
    name: string,
    content: string,
    _auth: unknown,
    options: { appProperties?: Record<string, string> } = {},
  ) {
    this.guard();
    const fileId = `file-${this.nextId++}`;
    this.files.set(fileId, {
      name,
      content,
      revision: 1,
      appProperties: options.appProperties ?? {},
    });
    return fileId;
  }

  async createFolder(
    name: string,
    _auth: unknown,
    options: { appProperties?: Record<string, string> } = {},
  ) {
    this.guard();
    const fileId = `folder-${this.nextId++}`;
    this.files.set(fileId, {
      name,
      content: "",
      revision: 1,
      appProperties: options.appProperties ?? {},
    });
    return fileId;
  }

  async write(fileId: string, content: string) {
    this.guard();
    if (this.onBeforeWrite) await this.onBeforeWrite();
    const file = this.files.get(fileId);
    if (!file) throw new Error("not found");
    file.content = content;
    file.revision += 1;
    return { fileId };
  }

  /** Simulate another device publishing behind our back. */
  landForeignRevision(fileId: string, content: string) {
    const file = this.files.get(fileId);
    if (!file) throw new Error("not found");
    file.content = content;
    file.revision += 1;
  }

  vaultFile() {
    return [...this.files.values()].find((f) => f.appProperties["keyweb"] === "vault-v1");
  }

  asStore(): GoogleDriveFileStore {
    return this as unknown as GoogleDriveFileStore;
  }
}

export function fakeAuthenticator(seed: number) {
  const secret = new Uint8Array(32).fill(seed);
  const rawId = new Uint8Array(16).fill(seed + 100);
  const credential = {
    rawId: rawId.buffer.slice(0),
    response: {},
    getClientExtensionResults: () => ({ prf: { results: { first: secret.buffer.slice(0) } } }),
  };
  return {
    credentials: { create: async () => credential, get: async () => credential },
  } as unknown as Navigator;
}

