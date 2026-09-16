/*
 * What the web reader makes of `keepass-rich.kdbx`, for the Kotlin reader to match.
 *
 * The two KDBX readers are separate implementations — the web wraps `kdbxweb`,
 * the Kotlin one is written from the format — and they feed the *same vault*.
 * A field the phone calls `secret:Answer` and the browser calls `Answer` is
 * one field that has silently become two, on the one path where the user has
 * already deleted the original file.
 *
 * So the field names and values are frozen here and both suites check them.
 *
 * Regenerate with:  npm run fixture:kdbx-parity --workspace @keyweb/web
 */
import { writeFileSync, readFileSync } from "node:fs";
import { readKeePass } from "../src/vault/keepass.js";

const bytes = readFileSync(new URL("../../../fixtures/keepass-rich.kdbx", import.meta.url));
const preview = await readKeePass(
  bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer,
  "correct horse battery staple",
);

const entries = preview.entries
  .map((entry) => ({
    itemId: entry.itemId,
    // Sorted, because document order is not something the two readers promise
    // each other — the field *set* is.
    fields: Object.fromEntries(
      Object.entries(entry.fields)
        .filter(([, value]) => value !== undefined && value !== "")
        .sort(([a], [b]) => a.localeCompare(b)),
    ),
    versions: entry.versions.length,
    attachments: entry.attachments,
  }))
  .sort((a, b) => a.itemId.localeCompare(b.itemId));

writeFileSync(
  new URL("../../../fixtures/keepass-rich-fields.json", import.meta.url),
  `${JSON.stringify(
    {
      note: "Written by packages/web/scripts/emit-kdbx-parity-fixture.ts. Do not edit.",
      password: "correct horse battery staple",
      skipped: preview.skipped,
      keyringNames: preview.keyringNames,
      entries,
    },
    null,
    2,
  )}\n`,
);
console.log(`parity fixture: ${entries.length} entries`);
