import * as kdbxweb from "kdbxweb";
import { argon2d, argon2i, argon2id } from "hash-wasm";
import { HISTORY_LIMIT, ITEM_FIELDS, type ItemField, type VaultOp } from "@keyweb/vault-core";
import { formatOtp, parseOtp } from "./totp";
import { attachmentField, BLOB_KIND, decodeHlc, encodeHlc } from "@keyweb/vault-core";

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
  /**
   * Earlier versions of this entry, oldest first.
   *
   * KeePass keeps the whole prior entry each time you change one; Keyweb keeps
   * superseded *values* per field. Rather than translate between the two
   * shapes, each version is replayed as an ordinary edit stamped at the time
   * it actually happened, and the vault's own history falls out of that.
   */
  versions: ImportedVersion[];
  /** Files attached to this entry, with their bytes. */
  attachments: ImportedFile[];
};

/**
 * One attached file, ready to become an item of its own.
 *
 * `blobId` is the content hash, so the same document attached to three entries
 * is stored once and a re-import finds the copy it made last time rather than
 * making another.
 */
export type ImportedFile = {
  blobId: string;
  name: string;
  type: string;
  /** Base64. Empty when the file was past the size Keyweb will carry. */
  data: string;
  bytes: number;
};

/** One earlier version of an entry, with the moment it was superseded. */
export type ImportedVersion = {
  /** Milliseconds since the epoch, from KeePass's own modification time. */
  atMs: number;
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
  /**
   * Entries with nothing in them at all — no fields, no files, no history.
   *
   * The only thing it is safe to leave behind. Anything with content is
   * imported even if it has no title and no password, because "it had no
   * password so we dropped it" is how a secure note full of account numbers
   * disappears.
   */
  skipped: number;
  /**
   * Files too large to carry, named so the person can keep their original.
   *
   * A ceiling exists because the vault is re-encrypted and re-uploaded as one
   * document: a 200 MB video would make every later password change cost 200
   * MB. Refusing loudly is better than a vault that becomes unusable and
   * better than losing the file in silence.
   */
  oversized: { title: string; name: string; bytes: number }[];
  /** How many earlier versions came across, for the preview to report. */
  versions: number;
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

/**
 * The five names KeePass gives every entry, which Keyweb has its own names for.
 *
 * Everything *not* in here is a field somebody added themselves — a security
 * question, a backup PIN, an account number — and is exactly what used to be
 * dropped. `ItemField` is deliberately open-keyed, so these need no schema
 * change to carry: they come through under the name KeePass knew them by.
 */
const KDBX_STANDARD: Record<string, ItemField> = {
  Title: "title",
  UserName: "username",
  Password: "password",
  URL: "url",
  Notes: "note",
};

/**
 * Where a one-time-code seed hides.
 *
 * KeePass has no field for it, so every tool invented one. KeePassXC and
 * KeeWeb write `otp`; the older KeePass plugin writes `TOTP Seed` with its
 * settings alongside. All of them hold either an `otpauth://` URI or a bare
 * base32 secret, and `parseOtp` already accepts both.
 */
const OTP_NAMES = new Set(["otp", "totp", "totp seed", "totp-seed", "otpauth"]);

/** Settings that only make sense beside a seed we have already normalised. */
const OTP_NOISE = new Set(["totp settings", "totp-settings"]);

/**
 * What to call an imported field inside Keyweb.
 *
 * A protected string becomes `secret:<name>`, which is what makes it masked
 * on screen rather than printed beside the username — `isSecretField` keys off
 * that prefix, so a KeePass field the user marked as protected stays
 * protected here without anybody maintaining a list.
 *
 * A custom field whose name collides with one of Keyweb's own keys is
 * prefixed rather than allowed to overwrite it. A KeePass field called
 * "folder" must not be able to move the entry.
 */
function importedFieldName(name: string, protectedValue: boolean): ItemField {
  const collides = (ITEM_FIELDS as readonly string[]).includes(name.toLowerCase());
  const safe = collides ? `custom:${name}` : name;
  return protectedValue ? `secret:${safe}` : safe;
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
  const oversized: ImportPreview["oversized"] = [];
  let ungrouped = 0;
  let skipped = 0;
  let versionCount = 0;

  const walk = async (group: kdbxweb.KdbxGroup, path: string[]): Promise<void> => {
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
      const fields = readFields(entry);
      const files = await readAttachments(entry, oversized, fields.title ?? "Untitled");
      const versions = readVersions(entry);

      // The only entry safe to leave behind is one with nothing in it. The
      // old rule dropped anything with no title and no password, which is
      // how a secure note holding account numbers and a scanned document
      // disappears from a vault somebody then deletes the original of.
      const empty =
        Object.values(fields).every((value) => !value) &&
        files.length === 0 &&
        versions.length === 0;
      if (empty) {
        skipped += 1;
        continue;
      }

      fields.title = fields.title || "Untitled";
      // Drop the root group from the displayed path; its name is the
      // database's, not a folder the user made. An entry at the root is in
      // no folder, and says so by leaving this empty.
      fields.folder = here.slice(1).join(" / ");
      const tags = (entry.tags ?? []).filter(Boolean);
      if (tags.length > 0) fields.tags = tags.join(", ");

      versionCount += versions.length;

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
        versions,
        attachments: files,
      });
    }

    for (const child of group.groups) await walk(child, here);
  };

  for (const group of db.groups) await walk(group, []);

  return { entries, keyringNames, ungrouped, skipped, oversized, versions: versionCount };
}

/**
 * Every field on an entry, not the five Keyweb happens to have names for.
 *
 * This is the whole of the fix. KeePass entries carry whatever their owner
 * put on them — security questions and their answers, backup PINs, account
 * numbers, recovery codes — and the import used to read five keys and walk
 * away from the rest without saying so.
 */
function readFields(entry: kdbxweb.KdbxEntry): Partial<Record<ItemField, string>> {
  const fields: Partial<Record<ItemField, string>> = {};
  for (const [rawName, rawValue] of entry.fields) {
    const name = String(rawName);
    const value = text(rawValue);
    if (!value) continue;

    const standard = KDBX_STANDARD[name];
    if (standard) {
      fields[standard] = value;
      continue;
    }

    const lower = name.toLowerCase();
    // Settings for a seed we are about to normalise into an otpauth URI, which
    // carries its own digits and period. Keeping them would leave two sources
    // of truth that can disagree.
    if (OTP_NOISE.has(lower)) continue;
    if (OTP_NAMES.has(lower)) {
      // Both an `otpauth://` URI and a bare base32 seed end up as a URI, which
      // is the shape Keyweb's own TOTP code reads.
      try {
        fields.otp = formatOtp(parseOtp(value));
      } catch {
        // Unreadable as a seed — but it is still the user's data, so it is
        // carried through under its own name rather than thrown away.
        fields[importedFieldName(name, isProtected(rawValue))] = value;
      }
      continue;
    }

    fields[importedFieldName(name, isProtected(rawValue))] = value;
  }
  return fields;
}

/**
 * Earlier versions of an entry, oldest first.
 *
 * Capped at the vault's own history limit: KeePass will happily keep hundreds
 * of versions per entry, and every one of them would become an operation in
 * the outbox and a value in the encrypted vault forever.
 */
function readVersions(entry: kdbxweb.KdbxEntry): ImportedVersion[] {
  const history = entry.history ?? [];
  return history
    .slice(-HISTORY_LIMIT)
    .map((version) => ({
      atMs: version.times?.lastModTime?.getTime() ?? 0,
      fields: readFields(version),
    }))
    .filter((version) => version.atMs > 0 && Object.keys(version.fields).length > 0)
    .sort((a, b) => a.atMs - b.atMs);
}

function isProtected(value: unknown): boolean {
  return value instanceof kdbxweb.ProtectedValue;
}

/**
 * How big a single attached file Keyweb will carry.
 *
 * The vault is one encrypted document: it is sealed and uploaded whole, so the
 * size of the largest thing in it is paid on every later change. 10 MB covers
 * the scans, photos and recovery-code sheets people actually keep in a
 * password manager, and refuses the holiday video that would make the vault
 * unusable. Refusing out loud beats both a vault nobody can sync and a file
 * that disappears quietly.
 */
export const MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024;

/** The files on an entry, with their bytes, content-addressed. */
async function readAttachments(
  entry: kdbxweb.KdbxEntry,
  oversized: ImportPreview["oversized"],
  title: string,
): Promise<ImportedFile[]> {
  const files: ImportedFile[] = [];
  for (const [rawName, binary] of entry.binaries ?? []) {
    const name = String(rawName);
    const bytes = binaryBytes(binary);
    if (!bytes) continue;
    if (bytes.byteLength > MAX_ATTACHMENT_BYTES) {
      oversized.push({ title, name, bytes: bytes.byteLength });
      continue;
    }
    files.push({
      blobId: await blobIdFor(bytes),
      name,
      type: guessType(name),
      data: base64(bytes),
      bytes: bytes.byteLength,
    });
  }
  return files;
}

/** kdbxweb hands back a protected value, a raw buffer, or a reference. */
function binaryBytes(binary: unknown): Uint8Array | null {
  if (binary instanceof kdbxweb.ProtectedValue) return binary.getBinary();
  if (binary instanceof ArrayBuffer) return new Uint8Array(binary);
  if (binary instanceof Uint8Array) return binary;
  const value = (binary as { value?: unknown } | null)?.value;
  return value === undefined || value === binary ? null : binaryBytes(value);
}

/**
 * The item id a file's bytes are stored under: the hash of those bytes.
 *
 * Content-addressed so the same document attached to three entries is stored
 * once, and so re-importing the same file finds the copy it made last time
 * rather than making another.
 */
export async function blobIdFor(bytes: Uint8Array): Promise<string> {
  const view = new Uint8Array(bytes);
  const digest = await crypto.subtle.digest("SHA-256", view.buffer as ArrayBuffer);
  return `blob:${base64Url(new Uint8Array(digest))}`;
}

function base64(bytes: Uint8Array): string {
  let binary = "";
  // Chunked: spreading a multi-megabyte array into apply blows the stack.
  for (let i = 0; i < bytes.length; i += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  }
  return btoa(binary);
}

function base64Url(bytes: Uint8Array): string {
  return base64(bytes).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Enough to decide whether a viewer can show it; the name is the only clue. */
function guessType(name: string): string {
  const extension = name.toLowerCase().replace(/^.*\./, "");
  const known: Record<string, string> = {
    png: "image/png",
    jpg: "image/jpeg",
    jpeg: "image/jpeg",
    gif: "image/gif",
    webp: "image/webp",
    svg: "image/svg+xml",
    pdf: "application/pdf",
    txt: "text/plain",
  };
  return known[extension] ?? "application/octet-stream";
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
  return preview.entries.flatMap((entry) => {
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
    /*
     * Each earlier version is replayed as the edit it actually was, stamped
     * at the moment KeePass recorded it.
     *
     * Nothing has to translate between the two history shapes, because the
     * CRDT already keeps a superseded value whenever a later write replaces
     * it — so replaying old, then new, produces exactly the history Keyweb
     * would have had if the entry had been edited here all along.
     *
     * The timestamps are built directly rather than taken from the clock:
     * asking the live clock for 2019 is impossible, and *observing* 2019
     * would do nothing, while a KeePass file with a clock set to 2099 would
     * otherwise pin this vault's clock forever. They are clamped below the
     * stamp this import is landing at for the same reason.
     */
    // Decoded only when there is history to place, so a caller that supplies
    // its own stamp shape is not forced to supply a real HLC for entries that
    // have no history at all.
    const base = entry.versions.length > 0 ? decodeHlc(ts) : null;
    const history = (base === null ? [] : entry.versions).map((version, index) =>
      ({
        kind: "item.put",
        opId: `${opId}:v${index}`,
        ts: encodeHlc({
          wall: Math.min(version.atMs, base!.wall - 1),
          counter: index,
          node: base!.node,
        }),
        itemId: entry.itemId,
        keyringId,
        fields: version.fields,
      }) satisfies VaultOp,
    );

    /*
     * Each attached file becomes an item of its own on the same keyring, and
     * the password gains a field pointing at it — the same shape as a file
     * attached by hand, because it is the same thing.
     *
     * The blob ops come first so a device replaying the outbox never sees a
     * password referring to bytes that have not arrived yet.
     */
    const files = entry.attachments.filter((file) => file.data !== "");
    const blobs = files.map(
      (file) =>
        ({
          kind: "item.put",
          opId: `${opId}:${file.blobId}`,
          ts,
          itemId: file.blobId,
          keyringId,
          fields: {
            kind: BLOB_KIND,
            name: file.name,
            type: file.type,
            size: String(file.bytes),
            "secret:data": file.data,
          },
        }) satisfies VaultOp,
    );

    return [
      ...history,
      ...blobs,
      {
        kind: "item.put",
        opId,
        ts,
        itemId: entry.itemId,
        keyringId,
        fields: {
          ...entry.fields,
          ...Object.fromEntries(
            files.map((file) => [attachmentField(file.blobId), file.name]),
          ),
        },
      } satisfies VaultOp,
    ];
  });
}
