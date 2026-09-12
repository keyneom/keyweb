import * as kdbxweb from "kdbxweb";
import { argon2d, argon2i, argon2id } from "hash-wasm";
import type { ItemField, VaultOp } from "@keyweb/vault-core";

/**
 * Reading a KeePass / KeeWeb database.
 *
 * KeeWeb is a reader for KeePass `.kdbx` files, so importing "from KeeWeb"
 * means parsing kdbx. The structure people build there — nested groups, tags —
 * is the organisation they have already done, and discarding it would turn a
 * curated vault into an undifferentiated list. So groups become keyrings,
 * the full group path is preserved as a folder, and tags come across intact.
 *
 * Two rules keep this safe:
 *
 *  - the master password and the decrypted database never leave this module's
 *    call stack. Nothing is written anywhere until the caller commits the
 *    operations, and the caller receives values, not the kdbx handle;
 *  - every entry keeps its KeePass UUID as its Keyweb item id. That makes
 *    re-importing the same file an *update* rather than a duplicate: the CRDT
 *    merges field by field against the item that is already there.
 */

/**
 * kdbxweb ships no Argon2, and KDBX4 -- what current KeePass and KeeWeb write
 * by default -- derives its key with it. Without this, every modern file fails
 * to open with "argon2 not implemented", so this is load-bearing rather than
 * an optimisation.
 *
 * Registered once, lazily, so the WebAssembly is only fetched if somebody
 * actually imports a file.
 */
let argon2Registered = false;

export function registerArgon2(): void {
  if (argon2Registered) return;
  argon2Registered = true;
  kdbxweb.CryptoEngine.setArgon2Impl(
    async (password, salt, memory, iterations, length, parallelism, type, version) => {
      const options = {
        password: new Uint8Array(password),
        salt: new Uint8Array(salt),
        parallelism,
        iterations,
        // Already kibibytes: kdbxweb divides the header's byte count by 1024
        // before calling us. Dividing again silently starves the KDF.
        memorySize: memory,
        hashLength: length,
        outputType: "binary" as const,
      };
      // The Argon2 variant, per the spec's own numbering: 0 = d, 1 = i, 2 = id.
      // KDBX4 files in the wild use Argon2d and Argon2id.
      const hash = await (type === 0
        ? argon2d({ ...options, version })
        : type === 2
          ? argon2id({ ...options, version })
          : argon2i({ ...options, version }));
      return hash.buffer as ArrayBuffer;
    },
  );
}

export type ImportedEntry = {
  /** Derived from the KeePass UUID, so a second import updates rather than duplicates. */
  itemId: string;
  /** The top-level group, which becomes the keyring. */
  keyringName: string;
  /** The full group path, e.g. "Banking / Personal". */
  folder: string;
  fields: Partial<Record<ItemField, string>>;
};

export type ImportPreview = {
  entries: ImportedEntry[];
  /** Top-level group names, in the order they appear. */
  keyringNames: string[];
  skipped: number;
};

export class WrongMasterPassword extends Error {
  constructor() {
    super("That password didn't open the file. Check it and try again.");
    this.name = "WrongMasterPassword";
  }
}

export class UnreadableDatabase extends Error {
  constructor(message = "Keyweb couldn't read that file. Is it a KeePass or KeeWeb file?") {
    super(message);
    this.name = "UnreadableDatabase";
  }
}

function text(value: unknown): string {
  if (value == null) return "";
  // kdbx holds secrets in ProtectedValue, which only yields its text on demand.
  if (value instanceof kdbxweb.ProtectedValue) return value.getText();
  return String(value);
}

/**
 * Parse a `.kdbx` file.
 *
 * `password` is consumed here and not retained. The returned preview holds
 * plain strings so the caller can show and commit them without ever touching
 * the kdbx handle or its key.
 */
export async function readKeePass(
  bytes: ArrayBuffer,
  password: string,
  keyFile?: ArrayBuffer,
): Promise<ImportPreview> {
  registerArgon2();
  const credentials = new kdbxweb.Credentials(
    kdbxweb.ProtectedValue.fromString(password),
    keyFile ?? null,
  );

  let db: kdbxweb.Kdbx;
  try {
    db = await kdbxweb.Kdbx.load(bytes, credentials);
  } catch (cause) {
    const code = (cause as { code?: string })?.code;
    if (code === kdbxweb.Consts.ErrorCodes.InvalidKey) throw new WrongMasterPassword();
    throw new UnreadableDatabase();
  }

  const entries: ImportedEntry[] = [];
  const keyringNames: string[] = [];
  let skipped = 0;

  const walk = (group: kdbxweb.KdbxGroup, path: string[]) => {
    // KeePass keeps a recycle bin as a normal group; importing it would
    // resurrect things the user deliberately threw away.
    if (group.uuid?.id && db.meta.recycleBinUuid?.id === group.uuid.id) return;

    const here = [...path, text(group.name)].filter(Boolean);
    const top = here[1] ?? here[0] ?? "Imported";
    if (here.length > 1 && !keyringNames.includes(top)) keyringNames.push(top);

    for (const entry of group.entries) {
      const title = text(entry.fields.get("Title"));
      const password = text(entry.fields.get("Password"));
      if (!title && !password) {
        skipped += 1;
        continue;
      }
      const fields: Partial<Record<ItemField, string>> = {
        title: title || "Untitled",
        username: text(entry.fields.get("UserName")),
        password,
        url: text(entry.fields.get("URL")),
        note: text(entry.fields.get("Notes")),
        // Drop the root group from the displayed path; it is always "Database".
        folder: here.slice(1).join(" / "),
      };
      const tags = (entry.tags ?? []).filter(Boolean);
      if (tags.length > 0) fields.tags = tags.join(", ");

      entries.push({
        itemId: `kdbx:${entry.uuid.id}`,
        keyringName: top,
        folder: fields.folder ?? "",
        fields,
      });
    }

    for (const child of group.groups) walk(child, here);
  };

  for (const group of db.groups) walk(group, []);
  if (keyringNames.length === 0) keyringNames.push("Imported");

  return { entries, keyringNames, skipped };
}

/**
 * Turn a preview into operations.
 *
 * `keyringIds` maps a top-level group name to the keyring it should land on,
 * so the caller decides whether to create new keyrings or fold everything into
 * one. Stamping is left to the caller's clock, which is what keeps a re-import
 * ordered correctly against edits made in between.
 */
export function importOperations(
  preview: ImportPreview,
  keyringIds: Record<string, string>,
  stamp: () => { opId: string; ts: string },
): VaultOp[] {
  return preview.entries.map((entry) => {
    const { opId, ts } = stamp();
    return {
      kind: "item.put",
      opId,
      ts,
      itemId: entry.itemId,
      keyringId: keyringIds[entry.keyringName] ?? Object.values(keyringIds)[0] ?? "personal",
      fields: entry.fields,
    } satisfies VaultOp;
  });
}
