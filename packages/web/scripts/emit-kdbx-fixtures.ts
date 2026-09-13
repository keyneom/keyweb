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

writeFileSync(
  new URL("../../../fixtures/keepass-password.txt", import.meta.url),
  `${PASSWORD}\n`,
);
