/*
 * A KeePass database holding everything the import used to throw away.
 *
 * The other fixtures are ordinary vaults and pass whatever the import does to
 * them — which is why the loss went unnoticed. This one is built out of the
 * cases that actually go missing: custom fields, a protected custom field, a
 * one-time-code seed, an entry with files attached, per-entry history, and an
 * entry with no title and no password that is nevertheless full of somebody's
 * account numbers.
 *
 * Regenerate with:  npm run fixture:kdbx-rich --workspace @keyweb/web
 */
import { writeFileSync } from "node:fs";
import * as kdbxweb from "kdbxweb";
import { registerArgon2 } from "../src/vault/keepass.js";

const PASSWORD = "correct horse battery staple";

registerArgon2();
const credentials = new kdbxweb.Credentials(kdbxweb.ProtectedValue.fromString(PASSWORD));
const db = kdbxweb.Kdbx.create(credentials, "Everything");
const group = db.createGroup(db.getDefaultGroup(), "Banking");

// An ordinary login that has been changed twice, so it has history.
const bank = db.createEntry(group);
bank.fields.set("Title", "Chase Bank");
bank.fields.set("UserName", "maria@example.com");
bank.fields.set("Password", kdbxweb.ProtectedValue.fromString("first-password"));
bank.fields.set("Security question", "First pet's name");
bank.fields.set("Security answer", kdbxweb.ProtectedValue.fromString("Rufus"));
bank.fields.set("Account number", "00112233");
bank.pushHistory();
bank.times.lastModTime = new Date("2019-03-04T10:00:00Z");
bank.fields.set("Password", kdbxweb.ProtectedValue.fromString("second-password"));
bank.pushHistory();
bank.history[0]!.times.lastModTime = new Date("2019-03-04T10:00:00Z");
bank.history[1]!.times.lastModTime = new Date("2021-07-19T09:30:00Z");
bank.fields.set("Password", kdbxweb.ProtectedValue.fromString("current-password"));
bank.times.lastModTime = new Date("2024-01-02T08:00:00Z");

// A login with two-factor set up, written the way KeePassXC writes it.
const mail = db.createEntry(group);
mail.fields.set("Title", "Webmail");
mail.fields.set("UserName", "maria@example.com");
mail.fields.set("Password", kdbxweb.ProtectedValue.fromString("mail-password"));
mail.fields.set(
  "otp",
  kdbxweb.ProtectedValue.fromString(
    "otpauth://totp/Webmail:maria?secret=JBSWY3DPEHPK3PXP&issuer=Webmail&digits=6&period=30",
  ),
);

// The one that used to vanish entirely: no title, no password, and somebody's
// passport and bank details in it.
const note = db.createEntry(group);
note.fields.set("Passport number", kdbxweb.ProtectedValue.fromString("X1234567"));
note.fields.set("Sort code", "20-00-00");
note.fields.set("Notes", "Keep this somewhere safe");

// An entry carrying a file, which nothing in Keyweb can store yet.
const scan = db.createEntry(group);
scan.fields.set("Title", "Recovery codes");
scan.fields.set("Password", kdbxweb.ProtectedValue.fromString("unused"));
const file = await db.binaries.add(new TextEncoder().encode("11111111\n22222222\n"));
scan.binaries.set("recovery-codes.txt", file.value);

// A field named exactly like one of Keyweb's own keys, which must not be able
// to overwrite it — a KeePass field called "folder" must not move the entry.
const tricky = db.createEntry(group);
tricky.fields.set("Title", "Odd names");
tricky.fields.set("Password", kdbxweb.ProtectedValue.fromString("odd"));
tricky.fields.set("folder", "not-a-real-folder");
tricky.fields.set("kind", "not-a-real-kind");

const bytes = await db.save();
writeFileSync(new URL("../../../fixtures/keepass-rich.kdbx", import.meta.url), Buffer.from(bytes));
console.log("wrote fixtures/keepass-rich.kdbx");
