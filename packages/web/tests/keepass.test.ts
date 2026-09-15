import { describe, expect, it } from "vitest";
import * as kdbxweb from "kdbxweb";
import { applyOps, emptyVault, itemField, visibleItems } from "@keyweb/vault-core";
import {
  importOperations,
  readKeePass,
  registerArgon2,
  WrongMasterPassword,
  type ImportPreview,
} from "../src/vault/keepass";

const MASTER = "correct horse battery staple";

/**
 * Build a real KeePass database with the shape people actually create: nested
 * groups, tags, a couple of blank entries, and something in the recycle bin.
 */
async function buildDatabase(): Promise<ArrayBuffer> {
  // Writing a KDBX4 file needs Argon2 just as much as reading one does.
  registerArgon2();
  const credentials = new kdbxweb.Credentials(kdbxweb.ProtectedValue.fromString(MASTER));
  const db = kdbxweb.Kdbx.create(credentials, "Household");

  const banking = db.createGroup(db.getDefaultGroup(), "Banking");
  const personal = db.createGroup(banking, "Personal");
  const shopping = db.createGroup(db.getDefaultGroup(), "Shopping");

  const chase = db.createEntry(personal);
  chase.fields.set("Title", "Chase Bank");
  chase.fields.set("UserName", "maria@example.com");
  chase.fields.set("Password", kdbxweb.ProtectedValue.fromString("bank-secret"));
  chase.fields.set("URL", "chase.com");
  chase.fields.set("Notes", "Joint account");
  chase.tags = ["finance", "important"];

  const costco = db.createEntry(shopping);
  costco.fields.set("Title", "Costco");
  costco.fields.set("Password", kdbxweb.ProtectedValue.fromString("warehouse"));

  const blank = db.createEntry(shopping);
  blank.fields.set("UserName", "nothing-useful");

  const binned = db.createEntry(personal);
  binned.fields.set("Title", "Old card");
  binned.fields.set("Password", kdbxweb.ProtectedValue.fromString("expired"));
  db.remove(binned);

  return db.save();
}

let cached: ArrayBuffer | null = null;
async function database(): Promise<ArrayBuffer> {
  cached ??= await buildDatabase();
  return cached.slice(0);
}

function stamper() {
  let n = 0;
  return () => {
    n += 1;
    return { opId: `import-${n}`, ts: `00170000000000${n}-00000-import` };
  };
}

function keyringIds(preview: ImportPreview): Record<string, string> {
  return Object.fromEntries(preview.keyringNames.map((name) => [name, `ring-${name}`]));
}

describe("reading a KeePass or KeeWeb file", () => {
  it("refuses the wrong master password instead of importing nothing", async () => {
    await expect(readKeePass(await database(), "not the password")).rejects.toBeInstanceOf(
      WrongMasterPassword,
    );
  });

  it("brings the entries across with their passwords intact", async () => {
    const preview = await readKeePass(await database(), MASTER);
    const chase = preview.entries.find((e) => e.fields.title === "Chase Bank");
    expect(chase).toBeDefined();
    expect(chase!.fields.password).toBe("bank-secret");
    expect(chase!.fields.username).toBe("maria@example.com");
    expect(chase!.fields.url).toBe("chase.com");
    expect(chase!.fields.note).toBe("Joint account");
  });

  it("preserves the group hierarchy as keyrings and folders", async () => {
    const preview = await readKeePass(await database(), MASTER);
    expect(preview.keyringNames.sort()).toEqual(["Banking", "Shopping"]);

    const chase = preview.entries.find((e) => e.fields.title === "Chase Bank")!;
    expect(chase.keyringName).toBe("Banking");
    // The nested path survives, minus the root group.
    expect(chase.folder).toBe("Banking / Personal");

    const costco = preview.entries.find((e) => e.fields.title === "Costco")!;
    expect(costco.keyringName).toBe("Shopping");
    expect(costco.folder).toBe("Shopping");
  });

  it("preserves tags", async () => {
    const preview = await readKeePass(await database(), MASTER);
    const chase = preview.entries.find((e) => e.fields.title === "Chase Bank")!;
    expect(chase.fields.tags).toBe("finance, important");
  });

  it("skips empty entries and anything in the recycle bin", async () => {
    const preview = await readKeePass(await database(), MASTER);
    const titles = preview.entries.map((e) => e.fields.title);
    // Deliberately thrown away in KeePass; importing it would resurrect it.
    expect(titles).not.toContain("Old card");
    expect(preview.skipped).toBeGreaterThan(0);
  });
});

describe("importing into the vault", () => {
  it("lands entries on the keyring matching their top-level group", async () => {
    const preview = await readKeePass(await database(), MASTER);
    const ops = importOperations(preview, keyringIds(preview), "ring-ungrouped", stamper());
    const state = applyOps(emptyVault(), [
      {
        kind: "keyring.put",
        opId: "k1",
        ts: "001700000000000-00000-import",
        keyringId: "ring-Banking",
        name: "Banking",
      },
      {
        kind: "keyring.put",
        opId: "k2",
        ts: "001700000000000-00000-import",
        keyringId: "ring-Shopping",
        name: "Shopping",
      },
      ...ops,
    ]);

    expect(visibleItems(state)).toHaveLength(2);
    const chase = Object.values(state.items).find(
      (item) => itemField(item, "title") === "Chase Bank",
    )!;
    expect(chase.keyring.value).toBe("ring-Banking");
    expect(itemField(chase, "folder")).toBe("Banking / Personal");
    expect(itemField(chase, "tags")).toBe("finance, important");
  });

  it("updates rather than duplicates when the same file is imported again", async () => {
    const first = await readKeePass(await database(), MASTER);
    const rings = keyringIds(first);
    let state = applyOps(emptyVault(), importOperations(first, rings, "ring-ungrouped", stamper()));
    const afterFirst = Object.keys(state.items).length;

    // The same file, imported a second time: KeePass UUIDs carry over as item
    // ids, so the CRDT merges field by field instead of creating twins.
    const second = await readKeePass(await database(), MASTER);
    state = applyOps(state, importOperations(second, rings, "ring-ungrouped", stamper()));

    expect(Object.keys(state.items)).toHaveLength(afterFirst);
  });

  it("lets a later edit in the source file win on re-import", async () => {
    const preview = await readKeePass(await database(), MASTER);
    const rings = keyringIds(preview);
    let state = applyOps(emptyVault(), importOperations(preview, rings, "ring-ungrouped", stamper()));

    const changed: ImportPreview = {
      ...preview,
      entries: preview.entries.map((entry) =>
        entry.fields.title === "Chase Bank"
          ? { ...entry, fields: { ...entry.fields, password: "rotated-in-keepass" } }
          : entry,
      ),
    };
    // A stamper that runs later in causal time, as a real re-import would.
    let n = 100;
    state = applyOps(
      state,
      importOperations(changed, rings, "ring-ungrouped", () => {
        n += 1;
        return { opId: `re-${n}`, ts: `0017000000000${n}-00000-import` };
      }),
    );

    const chase = Object.values(state.items).find(
      (item) => itemField(item, "title") === "Chase Bank",
    )!;
    expect(itemField(chase, "password")).toBe("rotated-in-keepass");
  });
});
