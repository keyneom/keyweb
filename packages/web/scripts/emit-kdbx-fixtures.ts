/*
 * Writes real KeePass databases for the Kotlin reader to open.
 *
 * The Kotlin KDBX reader is implemented from the format, not ported from a
 * library, so the only honest way to know it is right is to point it at files
 * a real KeePass writer produced. `kdbxweb` is that writer, and it is the same
 * one the web app reads with — so a disagreement between the two platforms
 * shows up here rather than on someone's phone.
 *
 * Both formats are emitted on purpose. KDBX 4 is current; KDBX 3.1 is what
 * older KeePass installs still write, and they differ in key derivation, block
 * framing and where the inner stream parameters live. Testing only the newer
 * would leave the older path completely unexercised.
 *
 * Regenerate with:  npm run fixture:kdbx --workspace @keyweb/web
 */
import { writeFileSync } from "node:fs";
import * as kdbxweb from "kdbxweb";
import { registerArgon2 } from "../src/vault/keepass.js";

const PASSWORD = "correct horse battery staple";

async function build(version: 3 | 4): Promise<ArrayBuffer> {
  registerArgon2();
  const credentials = new kdbxweb.Credentials(kdbxweb.ProtectedValue.fromString(PASSWORD));
  const db = kdbxweb.Kdbx.create(credentials, "Household");
  if (version === 3) db.setVersion(3);

  const banking = db.createGroup(db.getDefaultGroup(), "Banking");
  const personal = db.createGroup(banking, "Personal");
  const shopping = db.createGroup(db.getDefaultGroup(), "Shopping");

  const chase = db.createEntry(personal);
  chase.fields.set("Title", "Chase Bank");
  chase.fields.set("UserName", "maria@example.com");
  chase.fields.set("Password", kdbxweb.ProtectedValue.fromString("bank-secret-🏦"));
  chase.fields.set("URL", "https://chase.com");
  chase.fields.set("Notes", "Joint account\nSecond line");
  chase.fields.set("Account number", kdbxweb.ProtectedValue.fromString("00112233"));
  chase.tags = ["finance", "important"];
  // A superseded password, so the history path is exercised: history entries
  // carry their own protected values and consume the same keystream.
  chase.pushHistory();
  chase.fields.set("Password", kdbxweb.ProtectedValue.fromString("rotated-later"));

  const costco = db.createEntry(shopping);
  costco.fields.set("Title", "Costco");
  costco.fields.set("Password", kdbxweb.ProtectedValue.fromString("warehouse"));

  const blank = db.createEntry(shopping);
  blank.fields.set("Title", "");

  const binned = db.createEntry(personal);
  binned.fields.set("Title", "Old card");
  binned.fields.set("Password", kdbxweb.ProtectedValue.fromString("expired"));
  db.remove(binned);

  return db.save();
}

for (const version of [3, 4] as const) {
  const bytes = new Uint8Array(await build(version));
  const target = new URL(`../../../fixtures/keepass-v${version}.kdbx`, import.meta.url);
  writeFileSync(target, bytes);
  console.log(`wrote ${target.pathname} (${bytes.length} bytes)`);
}

/**
 * A second database, about shape rather than contents.
 *
 * The database above puts every entry in a leaf group, which leaves the
 * arrangements real databases actually use untested: entries sitting directly
 * in a top-level group, entries loose at the database root, and subgroups that
 * share a name under different parents. Where an entry lands is decided by
 * exactly those cases, so they get a fixture of their own and both readers are
 * held to the same answer.
 */
async function buildStructure(): Promise<ArrayBuffer> {
  registerArgon2();
  const credentials = new kdbxweb.Credentials(kdbxweb.ProtectedValue.fromString(PASSWORD));
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

  // Deleting a whole group moves it into the recycle bin as a subgroup, with
  // its entries still inside. Skipping only the bin's direct children would
  // let these back in, and they would arrive wearing the bin's name as their
  // keyring -- deleted passwords, resurrected into a folder nobody made.
  const old = db.createGroup(root, "Old Stuff");
  add(old, "deleted-inside-a-deleted-group");
  db.remove(old);

  // A single deleted entry, which lands in the bin directly.
  const alone = db.createEntry(banking);
  alone.fields.set("Title", "deleted-on-its-own");
  alone.fields.set("Password", kdbxweb.ProtectedValue.fromString("x"));
  db.remove(alone);

  return db.save();
}

{
  const bytes = new Uint8Array(await buildStructure());
  const target = new URL("../../../fixtures/keepass-structure.kdbx", import.meta.url);
  writeFileSync(target, bytes);
  console.log(`wrote ${target.pathname} (${bytes.length} bytes)`);
}

writeFileSync(
  new URL("../../../fixtures/keepass-password.txt", import.meta.url),
  `${PASSWORD}\n`,
);
