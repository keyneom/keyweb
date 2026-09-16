import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import {
  applyOps,
  attachmentsOf,
  createClock,
  emptyVault,
  isBlobItem,
  itemField,
  visibleItems,
} from "@keyweb/vault-core";
import { importOperations, readKeePass } from "../src/vault/keepass";

/**
 * Nothing a KeePass file holds may go missing without saying so.
 *
 * This is a data-loss suite rather than a feature suite, and the difference
 * matters: somebody imports, sees a count that looks right, deletes the
 * original file, and only finds out months later that their security answers
 * and their two-factor seeds were never carried across. There is no recovering
 * from that, so every case below is one that used to be dropped in silence.
 */

const bytes = readFileSync(new URL("../../../fixtures/keepass-rich.kdbx", import.meta.url));
const PASSWORD = "correct horse battery staple";

async function imported() {
  const preview = await readKeePass(
    bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer,
    PASSWORD,
  );
  const clock = createClock({ node: "test", physical: () => 1_760_000_000_000 });
  let n = 0;
  const ops = importOperations(
    preview,
    Object.fromEntries(preview.keyringNames.map((name) => [name, "ring"])),
    "ring",
    () => ({ opId: `op-${++n}`, ts: clock.now() }),
  );
  // The keyring has to exist or `visibleItems` hides everything on it, which
  // is the app's own rule rather than anything about importing.
  const keyring = {
    kind: "keyring.put" as const,
    opId: "ring",
    ts: clock.now(),
    keyringId: "ring",
    name: "Imported",
  };
  return { preview, state: applyOps(emptyVault(), [keyring, ...ops]) };
}

function byTitle(state: Awaited<ReturnType<typeof imported>>["state"], title: string) {
  const item = Object.values(state.items).find((i) => itemField(i, "title") === title);
  if (!item) throw new Error(`no imported item titled ${title}`);
  return item;
}

describe("what survives an import", () => {
  it("keeps custom fields somebody added themselves", async () => {
    const { state } = await imported();
    const bank = byTitle(state, "Chase Bank");
    expect(itemField(bank, "Security question")).toBe("First pet's name");
    expect(itemField(bank, "Account number")).toBe("00112233");
  });

  /**
   * A field KeePass marked protected has to stay protected here. The
   * `secret:` prefix is what `isSecretField` keys off, so this is the
   * difference between an answer being masked and being printed on screen
   * beside the username.
   */
  it("keeps a protected field protected", async () => {
    const { state } = await imported();
    const bank = byTitle(state, "Chase Bank");
    expect(itemField(bank, "secret:Security answer")).toBe("Rufus");
    expect(itemField(bank, "Security answer")).toBeUndefined();
  });

  it("recognises a one-time-code seed as one", async () => {
    const { state } = await imported();
    const otp = itemField(byTitle(state, "Webmail"), "otp");
    expect(otp).toContain("JBSWY3DPEHPK3PXP");
    // Normalised to the shape Keyweb's own TOTP code reads.
    expect(otp?.startsWith("otpauth://")).toBe(true);
  });

  /**
   * The entry that used to disappear completely: no title, no password, and
   * somebody's passport number in it.
   */
  it("keeps an entry that has no title and no password", async () => {
    const { preview, state } = await imported();
    expect(preview.skipped).toBe(0);
    const note = Object.values(state.items).find(
      (i) => itemField(i, "secret:Passport number") === "X1234567",
    );
    expect(note).toBeDefined();
    expect(itemField(note!, "Sort code")).toBe("20-00-00");
    expect(itemField(note!, "title")).toBe("Untitled");
  });

  it("keeps the earlier passwords, as history", async () => {
    const { state } = await imported();
    const bank = byTitle(state, "Chase Bank");
    expect(itemField(bank, "password")).toBe("current-password");
    const passwords = bank.history.filter((h) => h.field === "password").map((h) => h.value);
    expect(passwords).toContain("first-password");
    expect(passwords).toContain("second-password");
  });

  /** A KeePass field named like one of ours must not be able to act like one. */
  it("does not let a custom field overwrite a real one", async () => {
    const { state } = await imported();
    const odd = byTitle(state, "Odd names");
    expect(itemField(odd, "folder")).toBe("Banking");
    expect(itemField(odd, "custom:folder")).toBe("not-a-real-folder");
    expect(itemField(odd, "custom:kind")).toBe("not-a-real-kind");
  });

  /** The file itself, not a note about it having existed. */
  it("brings the attached file across, bytes and all", async () => {
    const { preview, state } = await imported();
    expect(preview.oversized).toEqual([]);

    const codes = byTitle(state, "Recovery codes");
    const files = attachmentsOf(codes);
    expect(files.map((file) => file.name)).toEqual(["recovery-codes.txt"]);

    const blob = state.items[files[0]!.blobId]!;
    expect(isBlobItem(blob)).toBe(true);
    expect(itemField(blob, "type")).toBe("text/plain");
    expect(atob(itemField(blob, "secret:data")!)).toBe("11111111\n22222222\n");
  });

  /** The bytes are an item, but they are not a password. */
  it("does not put the file in the password list", async () => {
    const { state } = await imported();
    const titles = visibleItems(state).map((item) => itemField(item, "title"));
    expect(titles).toContain("Recovery codes");
    expect(visibleItems(state).filter(isBlobItem)).toEqual([]);
  });

  it("reports how much history came across", async () => {
    const { preview } = await imported();
    expect(preview.versions).toBeGreaterThan(0);
  });

  /** A re-import must still update rather than duplicate. */
  it("is idempotent", async () => {
    const first = await imported();
    const second = await imported();
    expect(Object.keys(second.state.items).sort()).toEqual(Object.keys(first.state.items).sort());
  });
});
