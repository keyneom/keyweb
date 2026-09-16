import { describe, expect, it } from "vitest";
import {
  createClock,
  datasetOf,
  emptyVault,
  FakeRemote,
  MemoryVaultStorage,
  VaultSync,
  visibleItems,
  itemField,
  type VaultState,
} from "@keyweb/vault-core";
import type {
  SharedBackupParticipantV1,
  SharingInvitationV1,
  SharingPublicKeyResponseV1,
} from "@keyneom/sync-kit/sharing";
import type { SharingDatasetFileV1 } from "@keyneom/sync-kit/sharing";
import {
  createSharingInvitationV1,
  createSharingPublicKeyResponseV1,
  createWebCryptoSharingIdentity,
  type WebCryptoSharingIdentity,
} from "@keyneom/sync-kit/sharing/web-crypto";
import { KeywebSharing, type ShareStore } from "../src/vault/sharing/operations";
import { buildJoinLink, buildResponseLink, parseJoinLink, parseResponseLink } from "../src/vault/sharing/links";
import { SharingIdentity } from "../src/vault/sharing/identity";
import type { ProtectedSharingIdentityStore, ProtectedSharingIdentityV1 } from "@keyneom/sync-kit/sharing/web-passkey";
import { fakeAuthenticator } from "./helpers";

/**
 * Sharing a keyring end to end, with Drive replaced by a stand-in.
 *
 * What is being tested is Keyweb's half: which operations happen in which
 * order, what is remembered between the two links, and what a failure leaves
 * behind. The cryptography is sync-kit's and has its own suite; repeating it
 * here would only pin our call sites to one implementation of it.
 */

class MemoryIdentityStore implements ProtectedSharingIdentityStore {
  records = new Map<string, ProtectedSharingIdentityV1>();
  async load(appId: string) {
    return this.records.get(appId) ?? null;
  }
  async save(record: ProtectedSharingIdentityV1) {
    this.records.set(record.appId, record);
  }
  async delete(appId: string) {
    this.records.delete(appId);
  }
}

class MemoryShareStore implements ShareStore {
  values = new Map<string, unknown>();
  async readMeta(key: string) {
    return this.values.get(key);
  }
  async writeMeta(key: string, value: unknown) {
    this.values.set(key, JSON.parse(JSON.stringify(value)) as unknown);
  }
}

/**
 * Real signed invitations and replies, from sync-kit's own crypto.
 *
 * Hand-rolled stand-ins were rejected by the parsers, which is the parsers
 * doing their job — and it would have meant the link tests proved only that
 * two of my own functions agreed with each other.
 */
let owner: WebCryptoSharingIdentity | null = null;
let joiner: WebCryptoSharingIdentity | null = null;

async function ownerIdentity(): Promise<WebCryptoSharingIdentity> {
  owner ??= await createWebCryptoSharingIdentity();
  return owner;
}

async function joinerIdentity(): Promise<WebCryptoSharingIdentity> {
  joiner ??= await createWebCryptoSharingIdentity();
  return joiner;
}

async function anInvitation(input: {
  exchangeId: string;
  grants: { datasetId: string; role: string }[];
}): Promise<SharingInvitationV1> {
  return createSharingInvitationV1(await ownerIdentity(), {
    appId: "keyweb",
    appFolderId: "folder-1",
    exchangeId: input.exchangeId,
    recipientDrivePermissionId: "permission-1",
    requestedGrants: input.grants as never,
  });
}

async function aResponse(exchangeId: string): Promise<SharingPublicKeyResponseV1> {
  return createSharingPublicKeyResponseV1(await joinerIdentity(), {
    appId: "keyweb",
    exchangeId,
  });
}

/** A stand-in for Drive's shared files, recording what was asked of it. */
class FakeController {
  datasets = new Map<string, VaultState>();
  participants = new Map<string, SharedBackupParticipantV1[]>();
  created: string[] = [];
  adopted: string[] = [];
  invites: { emailAddress: string; datasetId: string; role: string }[] = [];
  revoked: { datasetId: string; keyId: string }[] = [];
  roles: { datasetId: string; keyId: string; role: string }[] = [];
  /** Datasets nobody may read yet — the owner has not accepted. */
  unreadable = new Set<string>();
  acceptFails = false;

  async createDataset(datasetId: string, value: VaultState) {
    this.created.push(datasetId);
    this.datasets.set(datasetId, value);
    this.participants.set(datasetId, [
      { keyId: (await ownerIdentity()).publicKey.keyId, role: "owner" } as SharedBackupParticipantV1,
    ]);
    return { datasetId, value };
  }

  async adoptDataset(datasetId: string) {
    this.adopted.push(datasetId);
    if (this.unreadable.has(datasetId) || !this.datasets.has(datasetId)) {
      throw Object.assign(new Error("not found"), { code: "not-found" });
    }
    return { datasetId, value: this.datasets.get(datasetId)! };
  }

  async inviteParticipantForLink(input: {
    emailAddress: string;
    requestedGrants: { datasetId: string; role: string }[];
  }) {
    const grant = input.requestedGrants[0]!;
    this.invites.push({ emailAddress: input.emailAddress, ...grant });
    const invitation = await anInvitation({
      exchangeId: `exchange-${this.invites.length}`,
      grants: input.requestedGrants,
    });
    const files: SharingDatasetFileV1[] = [
      { datasetId: grant.datasetId, fileId: `file-${grant.datasetId}`, role: grant.role as never },
    ];
    return { invitation, files };
  }

  async submitKeyResponseFromInvitation(invitation: SharingInvitationV1) {
    return aResponse(invitation.exchangeId);
  }

  async acceptKeyResponseFromPayload() {
    if (this.acceptFails) {
      return [{ datasetId: "x", status: "failed" as const, error: new Error("Drive said no") }];
    }
    return [{ datasetId: "x", status: "accepted" as const }];
  }

  async getDatasetParticipants(datasetId: string) {
    return {
      trustedOwnerKeyId: "owner-key",
      participants: this.participants.get(datasetId) ?? [],
    };
  }

  async setDatasetRole(input: { datasetId: string; keyId: string; role: string }) {
    this.roles.push(input);
  }

  async revokeDatasetKey(input: { datasetId: string; keyId: string }) {
    this.revoked.push(input);
  }
}

function rig() {
  const storage = new MemoryVaultStorage();
  const sync = new VaultSync({
    storage,
    remote: new FakeRemote(),
    clock: createClock({ node: "test" }),
  });
  const controller = new FakeController();
  const store = new MemoryShareStore();
  const identity = new SharingIdentity({
    store: new MemoryIdentityStore(),
    secret: async () => new Uint8Array(20).fill(7),
  });
  const sharing = new KeywebSharing({
    sync,
    controller: controller as never,
    identity,
    store,
  });
  return { storage, sync, controller, store, sharing, identity };
}

async function withHousehold() {
  const parts = rig();
  await parts.sync.putKeyring({ keyringId: "personal", name: "Just mine" });
  await parts.sync.putKeyring({ keyringId: "house", name: "Household" });
  await parts.sync.putItem({
    itemId: "wifi",
    keyringId: "house",
    fields: { title: "Wifi", password: "hunter2" },
  });
  await parts.sync.putItem({ itemId: "bank", keyringId: "personal", fields: { title: "Bank" } });
  return parts;
}

describe("sharing a keyring", () => {
  it("moves it into its own file and makes a link", async () => {
    const { sync, controller, sharing } = await withHousehold();
    const { link } = await sharing.shareKeyring({
      keyringId: "house",
      email: "rachel@example.com",
      role: "viewer",
    });

    const datasetId = datasetOf((await sync.state()).keyrings["house"]);
    expect(datasetId).toBeTruthy();
    expect(controller.created).toEqual([datasetId]);
    expect(link).toContain("sk-inv=");

    // The file that went up holds that keyring and nothing else.
    const published = controller.datasets.get(datasetId!)!;
    expect(Object.keys(published.keyrings)).toEqual(["house"]);
    expect(Object.keys(published.items)).toEqual(["wifi"]);
  });

  /**
   * The failure this guards is silent and unrecoverable: a second file means
   * the second person joins a copy that stops receiving anything, while the
   * owner's screen looks entirely normal.
   */
  it("shares the same file with a second person, not a second file", async () => {
    const { controller, sharing } = await withHousehold();
    await sharing.shareKeyring({ keyringId: "house", email: "a@example.com", role: "viewer" });
    await sharing.shareKeyring({ keyringId: "house", email: "b@example.com", role: "writer" });

    expect(controller.created.length).toBe(1);
    expect(new Set(controller.invites.map((invite) => invite.datasetId)).size).toBe(1);
    expect(controller.invites.map((invite) => invite.role)).toEqual(["viewer", "writer"]);
  });

  it("keeps the invitation, because accepting the reply needs it", async () => {
    const { sharing } = await withHousehold();
    await sharing.shareKeyring({ keyringId: "house", email: "rachel@example.com", role: "viewer" });

    const pending = await sharing.pendingInvites("house");
    expect(pending).toHaveLength(1);
    expect(pending[0]?.email).toBe("rachel@example.com");
    expect(pending[0]?.label).toBe("Household");
  });

  it("refuses to share a keyring that isn't there", async () => {
    const { sharing } = await withHousehold();
    await expect(
      sharing.shareKeyring({ keyringId: "ghost", email: "a@example.com", role: "viewer" }),
    ).rejects.toThrow(/doesn't exist/);
  });

  it("leaves nothing shared when Drive refuses the file", async () => {
    const { sync, controller, sharing } = await withHousehold();
    controller.createDataset = async () => {
      throw new Error("Drive said no");
    };
    await expect(
      sharing.shareKeyring({ keyringId: "house", email: "a@example.com", role: "viewer" }),
    ).rejects.toThrow("Drive said no");

    // The keyring moved into its own document, which is harmless and the step
    // that has to happen first — but nobody was invited to anything.
    expect(await sharing.pendingInvites("house")).toEqual([]);
    expect(visibleItems(await sync.state()).map((item) => item.id).sort()).toEqual([
      "bank",
      "wifi",
    ]);
  });
});

describe("accepting a reply", () => {
  it("refuses a reply that does not answer an invitation from here", async () => {
    const { sharing } = await withHousehold();
    await expect(
      sharing.acceptResponse(await aResponse("someone-else")),
    ).rejects.toThrow(/doesn't match an invitation/);
  });

  it("shows the fingerprint without wrapping the key", async () => {
    const { sharing } = await withHousehold();
    await sharing.shareKeyring({ keyringId: "house", email: "rachel@example.com", role: "viewer" });
    const reply = await aResponse("exchange-1");
    const preview = await sharing.previewResponse(reply);
    expect(preview.email).toBe("rachel@example.com");
    expect(preview.label).toBe("Household");
    expect(preview.fingerprint).toBeTruthy();
    expect(await sharing.pendingInvites("house")).toHaveLength(1);
  });

  it("finishes the invitation and stops it being reusable", async () => {
    const { controller, sharing } = await withHousehold();
    await sharing.shareKeyring({ keyringId: "house", email: "rachel@example.com", role: "viewer" });
    const exchangeId = controller.invites.length ? "exchange-1" : "";

    const reply = await aResponse(exchangeId);
    const done = await sharing.acceptResponse(reply);
    expect(done.email).toBe("rachel@example.com");
    expect(done.label).toBe("Household");
    expect(await sharing.pendingInvites("house")).toEqual([]);

    await expect(
      sharing.acceptResponse(reply),
    ).rejects.toThrow(/doesn't match an invitation/);
  });

  it("keeps the invitation when Drive could not finish", async () => {
    const { controller, sharing } = await withHousehold();
    await sharing.shareKeyring({ keyringId: "house", email: "rachel@example.com", role: "viewer" });
    controller.acceptFails = true;

    await expect(
      sharing.acceptResponse(await aResponse("exchange-1")),
    ).rejects.toThrow("Drive said no");
    // Still there, so trying again is possible rather than starting over.
    expect(await sharing.pendingInvites("house")).toHaveLength(1);
  });
});

describe("joining a keyring somebody shared", () => {
  const files: SharingDatasetFileV1[] = [
    { datasetId: "ds-1", fileId: "file-1", role: "viewer" },
  ];
  const anInvite = () =>
    anInvitation({ exchangeId: "exchange-1", grants: [{ datasetId: "ds-1", role: "viewer" }] });

  it("asks for the file grant before doing anything else", async () => {
    const { sharing } = rig();
    const order: string[] = [];
    await sharing.joinFromLink({
      invitation: await anInvite(),
      files,
      label: "Household",
      grantAccess: async () => {
        order.push("grant");
      },
    });
    expect(order).toEqual(["grant"]);
  });

  it("does not pretend to have joined when the file was not granted", async () => {
    const { sharing, sync } = rig();
    await expect(
      sharing.joinFromLink({
        invitation: await anInvite(),
        files,
        label: "Household",
        grantAccess: async () => {
          throw new Error("You didn't pick it.");
        },
      }),
    ).rejects.toThrow("You didn't pick it.");

    expect(await sharing.adoptJoinedKeyrings()).toEqual([]);
    expect(Object.keys((await sync.state()).keyrings)).toEqual([]);
  });

  /**
   * The keyring cannot arrive at join time: until the owner accepts, there is
   * nothing readable and nothing to name it. So it arrives later, on an
   * ordinary sync, and the person never has to go back to the link.
   */
  it("waits for the owner, then brings the keyring in by itself", async () => {
    const { sharing, sync, controller } = rig();
    controller.datasets.set("ds-1", {
      items: {
        wifi: {
          id: "wifi",
          keyring: { value: "house", ts: "001700000000000-00001-owner" },
          fields: { password: { value: "hunter2", ts: "001700000000000-00001-owner" } },
          deleted: { value: false, ts: "001700000000000-00000-owner" },
          history: [],
        },
      },
      keyrings: {
        house: {
          id: "house",
          name: { value: "Household", ts: "001700000000000-00001-owner" },
          deleted: { value: false, ts: "001700000000000-00000-owner" },
          dataset: { value: "", ts: "000000000000000-00000-" },
        },
      },
    });
    controller.unreadable.add("ds-1");

    await sharing.joinFromLink({
      invitation: await anInvite(), files, label: "Household", grantAccess: async () => {} });
    expect(await sharing.adoptJoinedKeyrings()).toEqual([]);

    controller.unreadable.delete("ds-1");
    expect(await sharing.adoptJoinedKeyrings()).toEqual(["Household"]);

    const state = await sync.state();
    expect(state.keyrings["house"]?.name.value).toBe("Household");
    expect(datasetOf(state.keyrings["house"])).toBe("ds-1");
    expect(visibleItems(state).map((item) => item.id)).toEqual(["wifi"]);
  });

  it("names the keyring from the file, never from the link", async () => {
    const { sharing, sync, controller } = rig();
    controller.datasets.set("ds-1", {
      items: {},
      keyrings: {
        house: {
          id: "house",
          name: { value: "Household", ts: "001700000000000-00001-owner" },
          deleted: { value: false, ts: "001700000000000-00000-owner" },
          dataset: { value: "", ts: "000000000000000-00000-" },
        },
      },
    });

    await sharing.joinFromLink({
      invitation: await anInvite(),
      files,
      // A link is just text somebody sent. It must not be able to decide what
      // anything in the vault is called.
      label: "Your bank details",
      grantAccess: async () => {},
    });
    await sharing.adoptJoinedKeyrings();

    expect((await sync.state()).keyrings["house"]?.name.value).toBe("Household");
  });

  it("does not adopt a keyring whose id this vault already has", async () => {
    // First-run vaults hardcode `"personal"`. Binding that id to their file
    // relocates every private password into it.
    const { sharing, sync, controller, storage } = await withHousehold();
    await sync.putItem({
      itemId: "bank",
      keyringId: "personal",
      fields: { password: "s3cret" },
    });
    controller.datasets.set("ds-1", {
      items: {},
      keyrings: {
        personal: {
          id: "personal",
          name: { value: "Household", ts: "001700000000000-00001-owner" },
          deleted: { value: false, ts: "001700000000000-00000-owner" },
          dataset: { value: "", ts: "000000000000000-00000-" },
        },
      },
    });
    await sharing.joinFromLink({
      invitation: await anInvite(), files, label: "Household", grantAccess: async () => {} });

    expect(await sharing.adoptJoinedKeyrings()).toEqual([]);

    const state = await sync.state();
    expect(datasetOf(state.keyrings["personal"])).toBeNull();
    expect(itemField(state.items["bank"]!, "password")).toBe("s3cret");
    expect((await storage.readState("ds-1")).items["bank"]).toBeUndefined();
  });

  it("does not adopt the same keyring twice", async () => {
    const { sharing, controller } = rig();
    controller.datasets.set("ds-1", {
      items: {},
      keyrings: {
        house: {
          id: "house",
          name: { value: "Household", ts: "001700000000000-00001-owner" },
          deleted: { value: false, ts: "001700000000000-00000-owner" },
          dataset: { value: "", ts: "000000000000000-00000-" },
        },
      },
    });
    await sharing.joinFromLink({
      invitation: await anInvite(), files, label: null, grantAccess: async () => {} });

    expect(await sharing.adoptJoinedKeyrings()).toEqual(["Household"]);
    expect(await sharing.adoptJoinedKeyrings()).toEqual([]);
  });
});

describe("who can see it", () => {
  it("marks which member is you, so the screen knows what to offer", async () => {
    const { sharing, controller, sync } = await withHousehold();
    await sharing.shareKeyring({ keyringId: "house", email: "rachel@example.com", role: "viewer" });
    const datasetId = datasetOf((await sync.state()).keyrings["house"])!;

    expect(await sharing.myFingerprint()).not.toBe("");
    controller.participants.set(datasetId, [
      { keyId: (await ownerIdentity()).publicKey.keyId, role: "owner" } as SharedBackupParticipantV1,
      { keyId: (await joinerIdentity()).publicKey.keyId, role: "viewer" } as SharedBackupParticipantV1,
    ]);

    const members = await sharing.members(datasetId);
    expect(members.map((member) => member.role)).toEqual(["owner", "viewer"]);
    // Nobody here is us: the fake owner key is not this device's identity.
    expect(members.every((member) => !member.you)).toBe(true);
    expect(members[0]?.fingerprint).toBeTruthy();
  });

  it("remembers the email of somebody let in, so they have a name not a key", async () => {
    const { sharing, controller, sync } = await withHousehold();
    await sharing.shareKeyring({ keyringId: "house", email: "rachel@example.com", role: "viewer" });
    const reply = await aResponse("exchange-1");
    await sharing.acceptResponse(reply);

    const datasetId = datasetOf((await sync.state()).keyrings["house"])!;
    controller.participants.set(datasetId, [
      { keyId: reply.keyId, role: "viewer" } as SharedBackupParticipantV1,
    ]);
    expect((await sharing.members(datasetId))[0]?.email).toBe("rachel@example.com");
  });

  it("passes a revoke through to the file, not just the screen", async () => {
    const { sharing, controller, sync } = await withHousehold();
    await sharing.shareKeyring({ keyringId: "house", email: "rachel@example.com", role: "viewer" });
    const datasetId = datasetOf((await sync.state()).keyrings["house"])!;

    const keyId = (await joinerIdentity()).publicKey.keyId;
    await sharing.revoke({ datasetId, keyId });
    expect(controller.revoked).toEqual([{ datasetId, keyId }]);
  });

  it("passes a role change through", async () => {
    const { sharing, controller, sync } = await withHousehold();
    await sharing.shareKeyring({ keyringId: "house", email: "rachel@example.com", role: "viewer" });
    const datasetId = datasetOf((await sync.state()).keyrings["house"])!;

    await sharing.setRole({
      datasetId,
      keyId: (await joinerIdentity()).publicKey.keyId,
      role: "writer",
    });
    expect(controller.roles[0]?.role).toBe("writer");
  });
});

describe("the two links", () => {
  it("carries the invitation and the files there and back", async () => {
    const files: SharingDatasetFileV1[] = [
      { datasetId: "ds-1", fileId: "file-1", role: "writer" },
    ];
    const link = buildJoinLink({
      invitation: await anInvitation({
        exchangeId: "exchange-1",
        grants: [{ datasetId: "ds-1", role: "writer" }],
      }),
      files,
      ownerEmail: "leslie@example.com",
      label: "Household",
      landing: "https://example.test/keyweb/",
    });

    const parsed = parseJoinLink(new URL(link).search);
    expect(parsed?.invitation.exchangeId).toBe("exchange-1");
    expect(parsed?.files).toEqual(files);
    expect(parsed?.ownerEmail).toBe("leslie@example.com");
    expect(parsed?.label).toBe("Household");
  });

  it("carries the reply back", async () => {
    const response = await aResponse("exchange-1");
    const link = buildResponseLink({ response, landing: "https://example.test/keyweb/" });
    expect(parseResponseLink(new URL(link).search)?.response.keyId).toBe(response.keyId);
  });

  it("is not a share link when it is just the app", () => {
    expect(parseJoinLink("")).toBeNull();
    expect(parseResponseLink("?grant=import")).toBeNull();
  });
});

describe("a vault that shares nothing", () => {
  it("behaves exactly as it did before any of this existed", async () => {
    const storage = new MemoryVaultStorage();
    const remote = new FakeRemote();
    const sync = new VaultSync({ storage, remote, clock: createClock({ node: "test" }) });

    await sync.putKeyring({ keyringId: "personal", name: "Just mine" });
    await sync.putItem({ itemId: "bank", keyringId: "personal", fields: { title: "Bank" } });
    await sync.sync();

    expect(await storage.knownDocuments()).toEqual([]);
    expect(remote.snapshot().items["bank"]).toBeDefined();
    expect(await sync.state()).toEqual(await storage.readState());
    expect(emptyVault()).toEqual({ items: {}, keyrings: {} });
  });
});

describe("what a reader may do", () => {
  const files: SharingDatasetFileV1[] = [
    { datasetId: "ds-1", fileId: "file-1", role: "viewer" },
  ];

  function sharedDocument(): VaultState {
    return {
      items: {},
      keyrings: {
        house: {
          id: "house",
          name: { value: "Household", ts: "001700000000000-00001-owner" },
          deleted: { value: false, ts: "001700000000000-00000-owner" },
          dataset: { value: "", ts: "000000000000000-00000-" },
        },
      },
    };
  }

  /**
   * Without this, someone shared a keyring as a reader types a new password
   * into it, sees "Saved", and never learns it went nowhere — the write is
   * refused at the file and the operation sits in the outbox forever.
   */
  it("marks a keyring shared as read-only", async () => {
    const { sharing, sync, controller } = rig();
    controller.datasets.set("ds-1", sharedDocument());

    await sharing.joinFromLink({
      invitation: await anInvitation({
        exchangeId: "exchange-1",
        grants: [{ datasetId: "ds-1", role: "viewer" }],
      }),
      files,
      label: "Household",
      grantAccess: async () => {},
    });
    await sharing.adoptJoinedKeyrings();

    const readOnly = await sharing.readOnlyKeyrings(await sync.state());
    expect([...readOnly]).toEqual(["house"]);
  });

  it("does not mark a keyring shared as a writer", async () => {
    const { sharing, sync, controller } = rig();
    controller.datasets.set("ds-1", sharedDocument());

    await sharing.joinFromLink({
      invitation: await anInvitation({
        exchangeId: "exchange-1",
        grants: [{ datasetId: "ds-1", role: "writer" }],
      }),
      files: [{ datasetId: "ds-1", fileId: "file-1", role: "writer" }],
      label: "Household",
      grantAccess: async () => {},
    });
    await sharing.adoptJoinedKeyrings();

    expect([...(await sharing.readOnlyKeyrings(await sync.state()))]).toEqual([]);
  });

  it("never marks a keyring of your own, even before Drive is asked", async () => {
    const { sharing, sync } = await withHousehold();
    await sharing.shareKeyring({ keyringId: "house", email: "a@example.com", role: "viewer" });
    expect([...(await sharing.readOnlyKeyrings(await sync.state()))]).toEqual([]);
  });

  it("says nothing about keyrings that were never shared", async () => {
    const { sharing, sync } = await withHousehold();
    expect([...(await sharing.readOnlyKeyrings(await sync.state()))]).toEqual([]);
  });

  /**
   * The link's grant is what is known until somebody asks Drive. Asking is
   * what makes a promotion take effect, and it has to, or a writer would stay
   * locked out of a keyring they were given write access to.
   */
  it("follows a promotion once Drive has been asked", async () => {
    const { sharing, sync, controller, identity } = rig();
    controller.datasets.set("ds-1", sharedDocument());
    await sharing.joinFromLink({
      invitation: await anInvitation({
        exchangeId: "exchange-1",
        grants: [{ datasetId: "ds-1", role: "viewer" }],
      }),
      files,
      label: "Household",
      grantAccess: async () => {},
    });
    await sharing.adoptJoinedKeyrings();
    expect([...(await sharing.readOnlyKeyrings(await sync.state()))]).toEqual(["house"]);

    const me = (await identity.getOrCreate()).publicKey.keyId;
    controller.participants.set("ds-1", [
      { keyId: me, role: "writer" } as SharedBackupParticipantV1,
    ]);
    const members = await sharing.members("ds-1");
    expect(members[0]?.you).toBe(true);

    expect([...(await sharing.readOnlyKeyrings(await sync.state()))]).toEqual([]);
  });
});

describe("a hostile share", () => {
  /**
   * The inviter controls every byte of their document, including the keyring
   * id. First-run vaults hardcode "personal", so naming their keyring that
   * would relocate every private password into their Drive file on bind.
   */
  function hostileDocument(keyringId: string): VaultState {
    return {
      items: {},
      keyrings: {
        [keyringId]: {
          id: keyringId,
          name: { value: "Household", ts: "001900000000000-00001-attacker" },
          deleted: { value: false, ts: "000000000000000-00000-" },
          dataset: { value: "", ts: "000000000000000-00000-" },
        },
      },
    };
  }

  async function joinHostile(keyringId: string) {
    const parts = await withHousehold();
    parts.controller.datasets.set("ds-evil", hostileDocument(keyringId));
    await parts.sharing.joinFromLink({
      invitation: await anInvitation({
        exchangeId: "exchange-9",
        grants: [{ datasetId: "ds-evil", role: "viewer" }],
      }),
      files: [{ datasetId: "ds-evil", fileId: "file-evil", role: "viewer" }],
      label: "Household",
      grantAccess: async () => {},
    });
    return parts;
  }

  it("cannot capture a keyring this vault already has", async () => {
    const { sharing, sync, storage } = await joinHostile("personal");
    await sharing.adoptJoinedKeyrings();

    // The private keyring is still private, and its passwords never moved.
    const state = await sync.state();
    expect(datasetOf(state.keyrings["personal"])).toBeNull();
    expect(itemField(state.items["bank"]!, "title")).toBe("Bank");
    expect(await storage.readState("ds-evil")).toEqual({ items: {}, keyrings: {} });
  });

  it("cannot rename the keyring it tried to capture", async () => {
    const { sharing, sync } = await joinHostile("personal");
    await sharing.adoptJoinedKeyrings();
    expect((await sync.state()).keyrings["personal"]?.name.value).toBe("Just mine");
  });

  it("still works for an id that is genuinely theirs", async () => {
    const { sharing, sync } = await joinHostile("their-ring");
    expect(await sharing.adoptJoinedKeyrings()).toEqual(["Household"]);
    expect(datasetOf((await sync.state()).keyrings["their-ring"])).toBe("ds-evil");
  });
});

