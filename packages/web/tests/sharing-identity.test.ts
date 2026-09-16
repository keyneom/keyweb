import { beforeEach, describe, expect, it } from "vitest";
import type {
  ProtectedSharingIdentityStore,
  ProtectedSharingIdentityV1,
} from "@keyneom/sync-kit/sharing/web-passkey";
import {
  KeywebSharingIdentityStore,
  SharingIdentity,
  SharingIdentityMissing,
} from "../src/vault/sharing/identity";
/**
 * The keypair that makes you a person other people can share with.
 *
 * The property everything else rests on: **one identity per Google account,
 * ever.** A second one is not a duplicate to be tidied up later — it is a
 * different participant, holding none of the grants the first one holds, and
 * the only cure is being re-invited to everything.
 */

class MemoryStore implements ProtectedSharingIdentityStore {
  records = new Map<string, ProtectedSharingIdentityV1>();
  offline = false;
  reads = 0;

  async load(appId: string): Promise<ProtectedSharingIdentityV1 | null> {
    this.reads += 1;
    if (this.offline) throw new Error("network unreachable");
    return this.records.get(appId) ?? null;
  }

  async save(record: ProtectedSharingIdentityV1): Promise<void> {
    if (this.offline) throw new Error("network unreachable");
    this.records.set(record.appId, record);
  }

  async delete(appId: string): Promise<void> {
    if (this.offline) throw new Error("network unreachable");
    this.records.delete(appId);
  }
}

/** The same printed recovery code every device of one person holds. */
const secret = new Uint8Array(20).fill(7);

function identity(store: ProtectedSharingIdentityStore, from: Uint8Array = secret) {
  return new SharingIdentity({ store, secret: async () => from });
}

describe("a sharing identity", () => {
  it("is generated once and then loaded, never generated again", async () => {
    const store = new MemoryStore();
    const first = await identity(store).getOrCreate();
    const second = await identity(store).getOrCreate();

    expect(second.publicKey.keyId).toBe(first.publicKey.keyId);
    expect(store.records.size).toBe(1);
  });

  /**
   * The whole reason it lives in the Google account rather than the device.
   * A second browser must be the *same* participant, with no ceremony, or
   * every device would have to be invited to its owner's own keyrings.
   */
  it("is the same person on a second device", async () => {
    const drive = new MemoryStore();
    const phone = new KeywebSharingIdentityStore({ local: new MemoryStore(), remote: drive });
    const laptop = new KeywebSharingIdentityStore({ local: new MemoryStore(), remote: drive });

    const onPhone = await identity(phone).getOrCreate();
    const onLaptop = await identity(laptop).getOrCreate();

    expect(onLaptop.publicKey.keyId).toBe(onPhone.publicKey.keyId);
  });

  /**
   * The reason the wrapping key comes from the recovery secret rather than
   * from the passkey: the phone has no passkey, and an identity only the
   * browser can open would make one person into two participants.
   */
  it("opens with the printed code alone, which is all the phone has", async () => {
    const drive = new MemoryStore();
    const browser = new KeywebSharingIdentityStore({ local: new MemoryStore(), remote: drive });
    const phone = new KeywebSharingIdentityStore({ local: new MemoryStore(), remote: drive });

    const created = await identity(browser).getOrCreate();
    // The phone shares nothing with the browser except this secret and Drive.
    const onPhone = await identity(phone, new Uint8Array(20).fill(7)).get();
    expect(onPhone.publicKey.keyId).toBe(created.publicKey.keyId);
  });

  it("does not open with the wrong code", async () => {
    const store = new MemoryStore();
    await identity(store).getOrCreate();
    await expect(identity(store, new Uint8Array(20).fill(9)).get()).rejects.toThrow();
  });

  it("refuses to invent one when only asked to load", async () => {
    const store = new MemoryStore();
    await expect(identity(store).get()).rejects.toBeInstanceOf(SharingIdentityMissing);
    expect(store.records.size).toBe(0);
  });

  it("keeps the private keys out of storage", async () => {
    const store = new MemoryStore();
    await identity(store).getOrCreate();
    const record = [...store.records.values()][0]!;

    expect(record.encryptedPrivateKeys).toBeTruthy();
    expect(JSON.stringify(record)).not.toContain("PRIVATE KEY");
    // And what comes back out cannot be exported again, even by us.
    const loaded = await identity(store).get();
    expect(loaded.signingPrivateKey.extractable).toBe(false);
    expect(loaded.encryptionPrivateKey.extractable).toBe(false);
  });

  it("does not ask the passkey twice for two callers at once", async () => {
    const store = new MemoryStore();
    await identity(store).getOrCreate();

    const live = identity(store);
    const [a, b] = await Promise.all([live.get(), live.get()]);
    expect(a).toBe(b);
    // Loaded once between them, not once each.
    expect(store.reads).toBe(2);
  });

  it("gives a short fingerprint two people can read to each other", async () => {
    const store = new MemoryStore();
    const value = SharingIdentity.fingerprint(await identity(store).getOrCreate());
    expect(value.length).toBeGreaterThan(3);
    expect(value).toBe(SharingIdentity.fingerprint(await identity(store).get()));
  });
});

describe("where the wrapped identity is kept", () => {
  let local: MemoryStore;
  let remote: MemoryStore;
  let store: KeywebSharingIdentityStore;

  beforeEach(() => {
    local = new MemoryStore();
    remote = new MemoryStore();
    store = new KeywebSharingIdentityStore({ local, remote });
  });

  it("caches what Drive had, so the next start needs no network", async () => {
    await identity(store).getOrCreate();
    expect(local.records.size).toBe(1);

    remote.offline = true;
    const offline = await identity(store).get();
    expect(offline.publicKey.keyId).toBeTruthy();
  });

  /**
   * A shared keyring has to open on a train. The identity is what decrypts
   * it, so a device that has one must never need Drive to use it.
   */
  it("opens a joined vault with no network at all", async () => {
    const created = await identity(store).getOrCreate();
    remote.offline = true;

    const afterRestart = new KeywebSharingIdentityStore({ local, remote });
    const loaded = await identity(afterRestart).get();
    expect(loaded.publicKey.keyId).toBe(created.publicKey.keyId);
  });

  /**
   * The failure mode this store exists to prevent. If an unreachable Drive
   * read as "there is no identity", the app would helpfully make a second
   * one — a different participant, holding none of the first one's grants.
   */
  it("says unreachable rather than absent, so no second identity is minted", async () => {
    remote.offline = true;
    await expect(identity(store).getOrCreate()).rejects.toThrow(/unreachable/);
    expect(local.records.size).toBe(0);
    expect(remote.records.size).toBe(0);
  });

  it("writes to Drive before the device, so a second device cannot miss it", async () => {
    remote.offline = true;
    await expect(identity(store).getOrCreate()).rejects.toThrow();
    // Nothing local either: a local-only identity would look established here
    // while the next device to sign in found nothing and made its own.
    expect(local.records.size).toBe(0);
  });
});
