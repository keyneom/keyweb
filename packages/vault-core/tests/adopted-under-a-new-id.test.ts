import { describe, expect, it } from "vitest";
import { createClock } from "../src/hlc.js";
import { MemoryVaultStorage, VAULT_DOCUMENT } from "../src/storage.js";
import { VaultSync } from "../src/sync.js";
import { FakeRemote } from "../src/testing.js";
import { itemField, visibleItems } from "../src/model.js";

/**
 * A share whose keyring id this vault is already using.
 *
 * Both first-run vaults call their keyring `"personal"`, so the day somebody
 * shares theirs is the day two different keyrings claim one id. Binding the
 * shared document onto the local `"personal"` would move this vault's private
 * passwords into somebody else's Drive file, so the share is adopted under a
 * fresh id instead — and from then on the two names for one keyring have to be
 * kept straight in both directions.
 *
 * The file keeps the id it already had, because the owner and every other
 * member are reading it. Only this device's own view uses the new id. An edit
 * made here has to travel back out under the *file's* id, or it lands in the
 * shared document as a keyring nobody else has and is silently invisible to
 * every person it was meant for.
 */
function joiner() {
  const storage = new MemoryVaultStorage();
  const vaultRemote = new FakeRemote();
  const sharedRemote = new FakeRemote();
  const sync = new VaultSync({
    storage,
    remote: vaultRemote,
    remoteFor: (documentId) =>
      documentId === VAULT_DOCUMENT ? vaultRemote : documentId === "ds-shared" ? sharedRemote : null,
    clock: createClock({ node: "joiner" }),
  });
  return { storage, sync };
}

/** What the owner's document holds: their keyring, under their id. */
async function adopted() {
  const parts = joiner();
  // This vault's own keyring, with a private password on it.
  await parts.sync.putKeyring({ keyringId: "personal", name: "Just mine" });
  await parts.sync.putItem({
    itemId: "private",
    keyringId: "personal",
    fields: { title: "My bank", password: "mine-alone" },
  });

  // The share arrives using the same id. Adopted under a fresh one.
  await parts.sync.adoptDocument("ds-shared", {
    items: {
      wifi: {
        id: "wifi",
        keyring: { value: "personal", ts: "001700000000001-00000-owner" },
        deleted: { value: false, ts: "001700000000001-00000-owner" },
        fields: {
          title: { value: "Wifi", ts: "001700000000001-00000-owner" },
          password: { value: "shared-one", ts: "001700000000001-00000-owner" },
        },
        history: [],
      },
    },
    keyrings: {
      personal: {
        id: "personal",
        name: { value: "Household", ts: "001700000000001-00000-owner" },
        deleted: { value: false, ts: "001700000000001-00000-owner" },
        dataset: { value: "", ts: "001700000000000-00000-owner" },
      },
    },
  });
  // The sequence `adoptAsNewKeyring` runs: the document, then a keyring under
  // the fresh id, then the binding that names the id the file uses.
  await parts.sync.putKeyring({ keyringId: "household-local", name: "Household" });
  await parts.sync.bindKeyring("household-local", "ds-shared", "personal");
  return parts;
}

describe("a shared keyring adopted under a new id", () => {
  it("shows both keyrings, and keeps the private one private", async () => {
    const { sync } = await adopted();
    const state = await sync.state();

    expect(state.keyrings["personal"]!.name.value).toBe("Just mine");
    expect(state.keyrings["household-local"]!.name.value).toBe("Household");
    // The private password stays on the private keyring.
    expect(state.items["private"]!.keyring.value).toBe("personal");
    expect(state.items["wifi"]!.keyring.value).toBe("household-local");
    expect(visibleItems(state)).toHaveLength(2);
  });

  it("never puts this vault's own passwords in the shared document", async () => {
    const { storage } = await adopted();
    const shared = await storage.readState("ds-shared");

    expect(shared.items["private"]).toBeUndefined();
    expect(Object.keys(shared.keyrings)).toEqual(["personal"]);
  });

  it("writes an edit back under the id the file already uses", async () => {
    const { sync, storage } = await adopted();
    await sync.putItem({
      itemId: "printer",
      keyringId: "household-local",
      fields: { title: "Printer", password: "from-the-joiner" },
    });

    const shared = await storage.readState("ds-shared");
    const added = shared.items["printer"];
    expect(added).toBeDefined();
    // The file's id, not this device's. Anything else is invisible to every
    // other member, because their sandbox filters on the id they bound.
    expect(added!.keyring.value).toBe("personal");
    expect(itemField(added!, "password")).toBe("from-the-joiner");
    // And it is not in the private vault at all.
    const vault = await storage.readState(VAULT_DOCUMENT);
    expect(vault.items["printer"]).toBeUndefined();
  });

  it("shows that edit back under the local id", async () => {
    const { sync } = await adopted();
    await sync.putItem({
      itemId: "printer",
      keyringId: "household-local",
      fields: { title: "Printer", password: "from-the-joiner" },
    });

    const state = await sync.state();
    expect(state.items["printer"]!.keyring.value).toBe("household-local");
    expect(itemField(state.items["printer"]!, "title")).toBe("Printer");
  });

  it("does not rename the owner's keyring from this side", async () => {
    const { sync, storage } = await adopted();
    await sync.putItem({
      itemId: "printer",
      keyringId: "household-local",
      fields: { title: "Printer" },
    });

    const shared = await storage.readState("ds-shared");
    expect(shared.keyrings["personal"]!.name.value).toBe("Household");
    expect(shared.keyrings["household-local"]).toBeUndefined();
  });
});
