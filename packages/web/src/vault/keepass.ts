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
  /**
   * The top-level group, which becomes the keyring, or null for an entry that
   * sits at the database root and is in no group at all.
   *
   * Null rather than a stand-in name, because there is no answer to give here:
   * where those entries should go is the user's decision, not something this
   * module can infer. Inventing a name made that decision silently and got it
   * wrong.
   */
  keyringName: string | null;
  /** The full group path, e.g. "Banking / Personal". Empty when in no group. */
  folder: string;
  fields: Partial<Record<ItemField, string>>;
};

/** Where the entries that are in no group should land. */
export type UngroupedDestination =
  | { kind: "new"; name: string }
  | { kind: "existing"; keyringId: string };

export type ImportPreview = {
  entries: ImportedEntry[];
  /** Top-level group names, in the order they appear. */
  keyringNames: string[];
  /** How many entries sit at the database root, in no group. */
  ungrouped: number;
  skipped: number;
};

/**
 * The name to suggest for a keyring holding the entries that are in no group.
 *
 * The file's own name is the best guess available: it is what the user calls
 * this collection of passwords, and it is already on screen, so the suggestion
 * does not come out of nowhere. The database's internal root group name is a
 * worse guess — it is often a leftover default like "NewDatabase" that the
 * user has never seen.
 */
export function suggestedKeyringName(fileName: string): string {
  const base = fileName.replace(/^.*[\\/]/, "");
  // Only the final extension: "Work passwords.v2.kdbx" keeps the ".v2".
  const withoutExtension = base.replace(/\.[^.]+$/, "");
  return withoutExtension.trim() || "Imported";
}

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
  let ungrouped = 0;
  let skipped = 0;

  const walk = (group: kdbxweb.KdbxGroup, path: string[]) => {
    // KeePass keeps a recycle bin as a normal group; importing it would
    // resurrect things the user deliberately threw away.
    if (group.uuid?.id && db.meta.recycleBinUuid?.id === group.uuid.id) return;

    const here = [...path, text(group.name)].filter(Boolean);
    // here[0] is the database's own root group, whose name is the database
    // name rather than a keyring, so the keyring is the group below it. An
    // entry sitting loose at the root has no group below it and no keyring to
    // name — the caller is asked where those should go.
    const top = here[1] ?? null;

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
        // Drop the root group from the displayed path; its name is the
        // database's, not a folder the user made. An entry at the root is in
        // no folder, and says so by leaving this empty.
        folder: here.slice(1).join(" / "),
      };
      const tags = (entry.tags ?? []).filter(Boolean);
      if (tags.length > 0) fields.tags = tags.join(", ");

      // Registered here rather than on entering the group, so the keyring
      // list is exactly the keyrings that will hold something. Registering on
      // entry created empty keyrings for groups that only contain subgroups,
      // and — worse — left a keyring the entries below name but which was
      // never created, so they were filed under whichever one came first.
      if (top === null) ungrouped += 1;
      else if (!keyringNames.includes(top)) keyringNames.push(top);

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

  return { entries, keyringNames, ungrouped, skipped };
}

/**
 * Turn a preview into operations.
 *
 * `keyringIds` maps a top-level group name to the keyring it should land on,
 * so the caller decides whether to create new keyrings or fold everything into
 * one. `ungroupedKeyringId` is where the entries that are in no group go — a
 * separate argument because that is a separate question, and the only one the
 * user has to answer. Stamping is left to the caller's clock, which is what
 * keeps a re-import ordered correctly against edits made in between.
 */
export function importOperations(
  preview: ImportPreview,
  keyringIds: Record<string, string>,
  ungroupedKeyringId: string,
  stamp: () => { opId: string; ts: string },
): VaultOp[] {
  return preview.entries.map((entry) => {
    const { opId, ts } = stamp();
    // An entry either names a group, which the caller has mapped, or names
    // none and goes where the caller said ungrouped entries go. There is no
    // third case and deliberately no fallback: a missing mapping used to be
    // silently absorbed, which is how entries ended up in folders nobody put
    // them in. Better to be obviously wrong than quietly wrong.
    const keyringId =
      entry.keyringName === null ? ungroupedKeyringId : keyringIds[entry.keyringName];
    if (!keyringId) {
      throw new Error(`No keyring was prepared for "${entry.keyringName}".`);
    }
    return {
      kind: "item.put",
      opId,
      ts,
      itemId: entry.itemId,
      keyringId,
      fields: entry.fields,
    } satisfies VaultOp;
  });
}
