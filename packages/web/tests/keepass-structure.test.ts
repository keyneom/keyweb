import { describe, expect, it } from "vitest";
import * as kdbxweb from "kdbxweb";
import { readKeePass, registerArgon2 } from "../src/vault/keepass";

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

  it("offers a keyring for every group that will hold something", async () => {
    const preview = await readKeePass(await database(), "pw");
    // "MyVault" is the database's root group, earning a keyring only because
    // an entry actually sits loose in it.
    //
    // Compared as a set: this reader takes a group's entries before its
    // subgroups, while the Kotlin reader walks the file in document order, so
    // the two put the root's own keyring in a different position. Both are
    // deterministic and neither is wrong, and nothing depends on the position
    // now that no entry falls back to "whichever came first".
    expect(new Set(preview.keyringNames)).toEqual(
      new Set(["MyVault", "Banking", "Shopping", "Work"]),
    );
  });

  it("lists the real groups in the order the file has them", async () => {
    const preview = await readKeePass(await database(), "pw");
    expect(preview.keyringNames.filter((name) => name !== "MyVault")).toEqual([
      "Banking",
      "Shopping",
      "Work",
    ]);
  });

  it("gives a root-level entry its own keyring rather than someone else's", async () => {
    const preview = await readKeePass(await database(), "pw");
    const loose = preview.entries.find((entry) => entry.fields.title === "loose-at-root");
    expect(loose).toBeDefined();

    // The failure this guards against is silent: an unregistered keyring name
    // is not an error at commit time, it just falls through to whichever
    // keyring came first, so the entry lands in a real folder it was never in.
    expect(preview.keyringNames).toContain(loose!.keyringName);
    expect(["Banking", "Shopping", "Work"]).not.toContain(loose!.keyringName);
    expect(loose!.folder).toBe("MyVault");
  });

  it("never names a keyring that was not offered", async () => {
    // Every entry's keyring must be one the commit step will have created.
    // This is the invariant that was broken, and it is worth asserting over
    // the whole file rather than for the one entry known to have tripped it.
    const preview = await readKeePass(await database(), "pw");
    for (const entry of preview.entries) {
      expect(preview.keyringNames).toContain(entry.keyringName);
    }
  });
});
