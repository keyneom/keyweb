import { attachmentsOf, isBlobItem, itemField, itemsOnKeyrings, type VaultState } from "./model.js";
import { fieldLabel, isSecretField, type ItemRecord } from "./model.js";
import { decodeHlc } from "./hlc.js";

/**
 * Getting everything back out.
 *
 * ## Why this exists at all
 *
 * The import was built on a promise: bring your KeePass file in and you can
 * delete the original. That promise is only honest if the door swings both
 * ways. Without an export, "your data is yours" means "your data is yours as
 * long as you keep using Keyweb" — and the backup in Drive is a sealed
 * envelope that only Keyweb can open, so it is a safety net, not a way out.
 *
 * ## Two formats, because one cannot be both things
 *
 * `csv` is what other password managers can read. It carries the columns they
 * all agree on and nothing else — no attachments, no per-entry history, and
 * custom fields only as text appended to the note. That loss is real, so
 * [csvOmissions] counts it and the screen says so before the file is written
 * rather than after it is relied on.
 *
 * `json` is lossless: every field under its own name, every file with its
 * bytes, every superseded value with the moment it was replaced. Nothing else
 * reads it today, which is exactly why it has to be a plain documented shape
 * rather than an internal dump — somebody should be able to write twenty lines
 * of script against it in ten years.
 *
 * ## Both are unencrypted, and that is the point
 *
 * An encrypted export that only Keyweb can open is the thing being escaped
 * from. So these are plaintext, and every screen that offers them says so in
 * those words.
 */

export type ExportedFile = {
  name: string;
  type: string;
  bytes: number;
  /** Base64 of the file's contents, exactly as the vault holds it. */
  data: string;
};

export type ExportedItem = {
  id: string;
  keyring: string;
  keyringName: string;
  title: string;
  username: string;
  password: string;
  url: string;
  note: string;
  /** The `otpauth://` URI, empty when there is no second factor. */
  otp: string;
  folder: string;
  tags: string;
  /**
   * Everything else, under the name its owner gave it.
   *
   * `secret` records whether Keyweb was masking it, which is information about
   * the field rather than about the value — dropping it would mean a
   * re-import could not tell a security answer from an account number.
   */
  fields: { name: string; value: string; secret: boolean }[];
  files: ExportedFile[];
  history: { field: string; value: string; at: string }[];
};

export type VaultExport = {
  format: "keyweb-export";
  version: 1;
  exportedAt: string;
  /** In the file itself, because a file outlives the screen that made it. */
  warning: string;
  keyrings: { id: string; name: string }[];
  items: ExportedItem[];
};

const WARNING =
  "This file is NOT encrypted. Anyone who opens it can read every password in it. " +
  "Keep it somewhere safe, or delete it once you have finished with it.";

export function exportVault(state: VaultState, now: Date = new Date()): VaultExport {
  const keyrings = Object.values(state.keyrings)
    .filter((ring) => !ring.deleted.value)
    .map((ring) => ({ id: ring.id, name: ring.name.value }));

  const items = itemsOnKeyrings(state)
    .filter((item) => !isBlobItem(item))
    .map((item) => exportItem(item, state))
    .sort((a, b) => a.title.localeCompare(b.title) || a.id.localeCompare(b.id));

  return {
    format: "keyweb-export",
    version: 1,
    exportedAt: now.toISOString(),
    warning: WARNING,
    keyrings,
    items,
  };
}

const PRESENTED = new Set(["title", "username", "password", "url", "note", "otp", "folder", "tags", "kind"]);

function exportItem(item: ItemRecord, state: VaultState): ExportedItem {
  const of = (name: string) => itemField(item, name) ?? "";

  const fields = Object.keys(item.fields)
    // The pointer at a file is plumbing; the file itself is in `files`.
    .filter((name) => !PRESENTED.has(name) && !name.startsWith("file:"))
    .filter((name) => of(name) !== "")
    .sort((a, b) => fieldLabel(a).localeCompare(fieldLabel(b)))
    .map((name) => ({ name: fieldLabel(name), value: of(name), secret: isSecretField(name) }));

  const files = attachmentsOf(item).flatMap((attachment) => {
    const blob = state.items[attachment.blobId];
    if (!blob) return [];
    const data = itemField(blob, "secret:data") ?? "";
    if (data === "") return [];
    return [
      {
        name: attachment.name,
        type: itemField(blob, "type") ?? "application/octet-stream",
        bytes: Number(itemField(blob, "size") ?? "0"),
        data,
      },
    ];
  });

  return {
    id: item.id,
    keyring: item.keyring.value,
    keyringName: state.keyrings[item.keyring.value]?.name.value ?? "",
    title: of("title"),
    username: of("username"),
    password: of("password"),
    url: of("url"),
    note: of("note"),
    otp: of("otp"),
    folder: of("folder"),
    tags: of("tags"),
    fields,
    files,
    history: item.history
      .filter((entry) => entry.value !== "")
      .map((entry) => ({
        field: fieldLabel(entry.field),
        value: entry.value,
        at: new Date(decodeHlc(entry.ts).wall).toISOString(),
      })),
  };
}

/** What a CSV cannot carry, counted so it can be said before the file is made. */
export type CsvOmissions = { files: number; history: number; customFields: number };

export function csvOmissions(exported: VaultExport): CsvOmissions {
  return {
    files: exported.items.reduce((n, item) => n + item.files.length, 0),
    history: exported.items.reduce((n, item) => n + item.history.length, 0),
    customFields: exported.items.reduce((n, item) => n + item.fields.length, 0),
  };
}

/**
 * The columns every other password manager agrees on.
 *
 * KeePass's own export order, because that is what the importers in KeePassXC,
 * Bitwarden, 1Password and Chrome were all written against. `TOTP` is appended
 * as a seventh column: importers that do not know it ignore a trailing column,
 * and the ones that do know it get the second factor across.
 *
 * Custom fields go into the note rather than into columns of their own —
 * extra columns are what makes an importer reject a file outright, and a
 * security answer buried in a note is recoverable where a rejected file is
 * not. The JSON export is the one that keeps them properly.
 */
export function exportCsv(exported: VaultExport): string {
  const header = ["Group", "Title", "Username", "Password", "URL", "Notes", "TOTP"];
  const rows = exported.items.map((item) => {
    const extras = item.fields.map((field) => `${field.name}: ${field.value}`);
    const dropped = item.files.map((file) => `[file not included in CSV: ${file.name}]`);
    const note = [item.note, ...extras, ...dropped].filter((part) => part !== "").join("\n");
    return [
      item.folder || item.keyringName,
      item.title,
      item.username,
      item.password,
      item.url,
      note,
      item.otp,
    ];
  });
  // CRLF and a trailing newline: RFC 4180, and Excel mangles anything else.
  return [header, ...rows].map((row) => row.map(csvCell).join(",")).join("\r\n") + "\r\n";
}

/**
 * One CSV cell.
 *
 * Always quoted. Conditional quoting is where CSV writers go wrong — a value
 * that gains a comma later stops being quoted correctly by a rule that was
 * written for the values present on the day — and no importer minds.
 *
 * A leading `=`, `+`, `-` or `@` is prefixed with a single quote. A password
 * beginning with `=` is a formula to Excel and Sheets, which will evaluate it:
 * the value on screen stops being the value in the vault, and in the worst
 * case the spreadsheet fetches a URL built out of somebody's password.
 */
function csvCell(value: string): string {
  const guarded = /^[=+\-@\t\r]/.test(value) ? `'${value}` : value;
  return `"${guarded.replace(/"/g, '""')}"`;
}
