import { describe, expect, it } from "vitest";
import { createClock } from "../src/hlc.js";
import { MemoryVaultStorage } from "../src/storage.js";
import { VaultSync } from "../src/sync.js";
import { FakeRemote } from "../src/testing.js";
import { itemField, itemsWithoutKeyring, visibleItems } from "../src/model.js";

/**
 * "I hit save, it said it worked, and the password is on neither device."
 *
 * The report that produced this file. Both clients were syncing correctly and
 * neither was lying about it: the password was committed, published, pulled
 * down by the phone and stored there. What both of them did was filter it out
 * of the list, because it named a keyring that was not in the vault — and
 * every screen was built from "items on a keyring that exists".
 *
 * The device in these tests does what the browser did: it saves against a
 * keyring id that nothing matches. The point of each case is that the password
 * survives that mistake visibly, on both sides of the sync.
 */
function device(node: string, remote: FakeRemote) {
  const storage = new MemoryVaultStorage();
  const sync = new VaultSync({ storage, remote, clock: createClock({ node }) });
  return { storage, sync };
}

describe("a password saved against a keyring that is not there", () => {
  it("is on screen on the device that saved it", async () => {
    const remote = new FakeRemote();
    const web = device("web", remote);
    await web.sync.putKeyring({ keyringId: "mine", name: "Just mine" });

    // The keyring id no screen ever offered: the old default when the map was
    // empty, or one deleted since.
    const state = await web.sync.putItem({
      itemId: "new",
      keyringId: "personal",
      fields: { title: "New password" },
    });

    expect(visibleItems(state).map((item) => itemField(item, "title"))).toContain("New password");
    expect(itemsWithoutKeyring(state).map((item) => item.id)).toEqual(["new"]);
  });

  it("is on screen on the phone it syncs to", async () => {
    const remote = new FakeRemote();
    const web = device("web", remote);
    await web.sync.putKeyring({ keyringId: "mine", name: "Just mine" });
    await web.sync.putItem({
      itemId: "new",
      keyringId: "personal",
      fields: { title: "New password" },
    });
    await web.sync.sync();

    const phone = device("phone", remote);
    await phone.sync.sync();

    const onPhone = await phone.sync.state();
    expect(visibleItems(onPhone).map((item) => itemField(item, "title"))).toContain(
      "New password",
    );
  });

  it("is genuinely in the backup, not merely on the screen of one device", async () => {
    const remote = new FakeRemote();
    const web = device("web", remote);
    await web.sync.putKeyring({ keyringId: "mine", name: "Just mine" });
    await web.sync.putItem({
      itemId: "new",
      keyringId: "personal",
      fields: { title: "New password" },
    });
    const outcome = await web.sync.sync();

    expect(outcome.status).toBe("published");
    expect(outcome.pending).toBe(0);
    const published = await remote.read();
    expect(published?.state.items["new"]).toBeDefined();
  });

  it("can be put right, and the fix reaches the other device", async () => {
    const remote = new FakeRemote();
    const web = device("web", remote);
    await web.sync.putKeyring({ keyringId: "mine", name: "Just mine" });
    await web.sync.putItem({
      itemId: "new",
      keyringId: "personal",
      fields: { title: "New password" },
    });
    await web.sync.moveItems(["new"], "mine");
    await web.sync.sync();

    const phone = device("phone", remote);
    await phone.sync.sync();
    const onPhone = await phone.sync.state();

    expect(itemsWithoutKeyring(onPhone)).toEqual([]);
    expect(onPhone.items["new"]!.keyring.value).toBe("mine");
  });
});
