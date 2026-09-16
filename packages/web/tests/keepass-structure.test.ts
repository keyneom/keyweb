import { describe, expect, it } from "vitest";
import * as kdbxweb from "kdbxweb";
import {
  importOperations,
  readKeePass,
  registerArgon2,
  suggestedKeyringName,
} from "../src/vault/keepass";

/**
 * Where an imported entry lands.
 *
 * The shipped fixture is two levels deep and every entry sits in a leaf group,
 * which leaves the arrangements most real databases actually use completely
 * unexercised: entries directly inside a top-level group, entries loose at the
 * database root, and same-named subgroups under different parents.
 *
 * Contents here are invented. The only question asked is which keyring and
 * which folder path each entry comes out with.
 */
async function database(): Promise<ArrayBuffer> {
  registerArgon2();
  const credentials = new kdbxweb.Credentials(kdbxweb.ProtectedValue.fromString("pw"));
  const db = kdbxweb.Kdbx.create(credentials, "MyVault");
  const root = db.getDefaultGroup();

  const add = (group: kdbxweb.KdbxGroup, title: string) => {
    const entry = db.createEntry(group);
    entry.fields.set("Title", title);
    entry.fields.set("Password", kdbxweb.ProtectedValue.fromString("x"));
  };

  add(root, "loose-at-root");

  const banking = db.createGroup(root, "Banking");
  add(banking, "direct-in-Banking");
  const bankingPersonal = db.createGroup(banking, "Personal");
  add(bankingPersonal, "in-Banking-Personal");
  add(db.createGroup(bankingPersonal, "Deep"), "three-deep");

  const shopping = db.createGroup(root, "Shopping");
  add(shopping, "direct-in-Shopping");
  // Same name as Banking's subgroup, under a different parent.
  add(db.createGroup(shopping, "Personal"), "in-Shopping-Personal");

  add(db.createGroup(root, "Work"), "direct-in-Work");

  return db.save();
}

describe("KeePass group structure", () => {
  it("puts every entry in the keyring and folder its group says", async () => {
    const preview = await readKeePass(await database(), "pw");
    const placed = Object.fromEntries(
      preview.entries.map((entry) => [
        entry.fields.title,
        { keyring: entry.keyringName, folder: entry.folder },
      ]),
    );

    expect(placed["direct-in-Banking"]).toEqual({ keyring: "Banking", folder: "Banking" });
    expect(placed["in-Banking-Personal"]).toEqual({
      keyring: "Banking",
      folder: "Banking / Personal",
    });
    expect(placed["three-deep"]).toEqual({
      keyring: "Banking",
      folder: "Banking / Personal / Deep",
    });
    expect(placed["direct-in-Shopping"]).toEqual({ keyring: "Shopping", folder: "Shopping" });
    expect(placed["in-Shopping-Personal"]).toEqual({
      keyring: "Shopping",
      folder: "Shopping / Personal",
    });
    expect(placed["direct-in-Work"]).toEqual({ keyring: "Work", folder: "Work" });
  });

  it("offers a keyring for each real group, and only those", async () => {
    const preview = await readKeePass(await database(), "pw");
    // The database's own root group is not among them: its name is the
    // database's, not a folder anyone made, and what happens to the entries
    // sitting in it is the user's choice rather than this module's.
    expect(preview.keyringNames).toEqual(["Banking", "Shopping", "Work"]);
  });

  it("counts the entries that are in no group instead of inventing one", async () => {
    const preview = await readKeePass(await database(), "pw");
    expect(preview.ungrouped).toBe(1);

    const loose = preview.entries.find((entry) => entry.fields.title === "loose-at-root");
    expect(loose!.keyringName).toBeNull();
    expect(loose!.folder).toBe("");
  });

  it("never names a keyring that was not offered", async () => {
    // The invariant that was broken. An unregistered name is not an error at
    // commit time — it used to fall through to whichever keyring came first,
    // so the entry landed in a real folder it had never been in.
    const preview = await readKeePass(await database(), "pw");
    for (const entry of preview.entries) {
      if (entry.keyringName === null) continue;
      expect(preview.keyringNames).toContain(entry.keyringName);
    }
  });

  it("sends the ungrouped entries where the caller said, and nowhere else", async () => {
    const preview = await readKeePass(await database(), "pw");
    const keyringIds = Object.fromEntries(
      preview.keyringNames.map((name) => [name, `ring-${name}`]),
    );
    let n = 0;
    const ops = importOperations(preview, keyringIds, "ring-chosen", () => {
      n += 1;
      return { opId: `op-${n}`, ts: `00170000000000${n}-00000-test` };
    });

    const byTitle = new Map(
      ops.map((op) => [
        (op as { fields: Record<string, string> }).fields["title"],
        (op as { keyringId: string }).keyringId,
      ]),
    );
    expect(byTitle.get("loose-at-root")).toBe("ring-chosen");
    // And the grouped ones are untouched by the choice.
    expect(byTitle.get("direct-in-Banking")).toBe("ring-Banking");
    expect(byTitle.get("in-Shopping-Personal")).toBe("ring-Shopping");
  });

  it("refuses to guess when a group has no keyring prepared", async () => {
    // Being loudly wrong beats being quietly wrong: the old fallback absorbed
    // this and put the entry in an unrelated folder.
    const preview = await readKeePass(await database(), "pw");
    expect(() =>
      importOperations(preview, {}, "ring-chosen", () => ({ opId: "x", ts: "t" })),
    ).toThrow(/No keyring was prepared/);
  });
});

describe("suggesting a name for the ungrouped keyring", () => {
  it("uses the file's name without its extension", () => {
    expect(suggestedKeyringName("Family passwords.kdbx")).toBe("Family passwords");
  });

  it("keeps everything but the last extension", () => {
    expect(suggestedKeyringName("Work passwords.v2.kdbx")).toBe("Work passwords.v2");
  });

  it("copes with a name that has no extension", () => {
    expect(suggestedKeyringName("passwords")).toBe("passwords");
  });

  it("strips any leading path a file input may hand over", () => {
    expect(suggestedKeyringName("C:\\Users\\me\\vault.kdbx")).toBe("vault");
    expect(suggestedKeyringName("/home/me/vault.kdbx")).toBe("vault");
  });

  it("falls back rather than suggesting an empty name", () => {
    expect(suggestedKeyringName(".kdbx")).toBe("Imported");
    expect(suggestedKeyringName("   ")).toBe("Imported");
  });
});

describe("the ungrouped keyring colliding with a group name", () => {
  /**
   * The suggested name for the ungrouped keyring is the file's own, so it can
   * easily match a group inside that same file — "Family passwords.kdbx" with
   * a "Family passwords" group in it. Both then ask for a keyring of that
   * name, and if the second ask does not see the first, the import ends with
   * two keyrings wearing the same name and the passwords split between them.
   *
   * Nothing errors when that happens, which is why it is worth a test: it
   * looks like a duplicate folder appearing for no reason.
   */
  async function fileWithGroupNamedLikeTheFile(): Promise<ArrayBuffer> {
    registerArgon2();
    const credentials = new kdbxweb.Credentials(kdbxweb.ProtectedValue.fromString("pw"));
    const db = kdbxweb.Kdbx.create(credentials, "MyVault");
    const root = db.getDefaultGroup();
    const add = (group: kdbxweb.KdbxGroup, title: string) => {
      const entry = db.createEntry(group);
      entry.fields.set("Title", title);
      entry.fields.set("Password", kdbxweb.ProtectedValue.fromString("x"));
    };
    add(root, "loose-one");
    add(db.createGroup(root, "Family passwords"), "in-the-group");
    return db.save();
  }

  it("uses one keyring, not two, when the names collide", async () => {
    const preview = await readKeePass(await fileWithGroupNamedLikeTheFile(), "pw");
    expect(preview.ungrouped).toBe(1);
    expect(preview.keyringNames).toEqual(["Family passwords"]);

    // Both paths ask for "Family passwords": the group, and the ungrouped
    // destination named after the file.
    const minted = new Map<string, string>();
    let next = 0;
    const keyringFor = (name: string) => {
      const seen = minted.get(name);
      if (seen !== undefined) return seen;
      const id = `ring-${++next}`;
      minted.set(name, id);
      return id;
    };
    const keyringIds = Object.fromEntries(
      preview.keyringNames.map((name) => [name, keyringFor(name)]),
    );
    const ungroupedId = keyringFor("Family passwords");

    expect(minted.size).toBe(1);
    expect(ungroupedId).toBe(keyringIds["Family passwords"]);

    const ops = importOperations(preview, keyringIds, ungroupedId, () => ({
      opId: `op-${++next}`,
      ts: `0017000000000${next}-00000-test`,
    }));
    const rings = new Set(ops.map((op) => (op as { keyringId: string }).keyringId));
    expect(rings.size).toBe(1);
  });
});
