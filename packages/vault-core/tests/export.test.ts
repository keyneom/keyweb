import { describe, expect, it } from "vitest";
import {
  applyOps,
  csvOmissions,
  emptyVault,
  encodeHlc,
  exportCsv,
  exportVault,
} from "../src/index.js";

/**
 * Getting everything back out.
 *
 * The import was built on a promise — bring your KeePass file in and you can
 * delete the original — and that promise is only honest if the door swings
 * both ways. So these tests are about the door: everything that went in comes
 * back out, and where a format genuinely cannot carry something, it says so
 * rather than dropping it quietly.
 */

function at(n: number) {
  return encodeHlc({ wall: 1_700_000_000_000 + n, counter: 0, node: "test" });
}

function vault() {
  return applyOps(emptyVault(), [
    { kind: "keyring.put", opId: "k", ts: at(0), keyringId: "home", name: "Home" },
    {
      kind: "item.put",
      opId: "blob",
      ts: at(1),
      itemId: "blob:abc",
      keyringId: "home",
      fields: {
        kind: "blob",
        name: "passport.png",
        type: "image/png",
        size: "12",
        "secret:data": "aGVsbG8gd29ybGQ=",
      },
    },
    {
      kind: "item.put",
      opId: "a",
      ts: at(2),
      itemId: "bank",
      keyringId: "home",
      fields: {
        title: "Chase Bank",
        username: "maria@example.com",
        password: "first",
        url: "chase.com",
        note: "Joint account",
        otp: "otpauth://totp/Chase?secret=JBSWY3DPEHPK3PXP",
        folder: "Banking / Personal",
        "Account number": "00112233",
        "secret:Security answer": "Rufus",
        "file:blob:abc": "passport.png",
      },
    },
    { kind: "item.put", opId: "b", ts: at(3), itemId: "bank", keyringId: "home", fields: { password: "second" } },
  ]);
}

describe("taking everything out", () => {
  it("carries every part of an item", () => {
    const out = exportVault(vault());
    expect(out.items).toHaveLength(1);
    const bank = out.items[0]!;

    expect(bank.title).toBe("Chase Bank");
    expect(bank.password).toBe("second");
    expect(bank.otp).toContain("JBSWY3DPEHPK3PXP");
    expect(bank.keyringName).toBe("Home");
    // Custom fields under the name their owner gave them, with the masking
    // recorded — a re-import could not tell a security answer from an account
    // number without it.
    expect(bank.fields).toEqual([
      { name: "Account number", value: "00112233", secret: false },
      { name: "Security answer", value: "Rufus", secret: true },
    ]);
    // The file itself, not a note that one existed.
    expect(bank.files).toEqual([
      { name: "passport.png", type: "image/png", bytes: 12, data: "aGVsbG8gd29ybGQ=" },
    ]);
    // And what the password used to be.
    expect(bank.history).toEqual([
      { field: "password", value: "first", at: new Date(1_700_000_000_002).toISOString() },
    ]);
  });

  /** A file is an item in the vault, and must not come out as a password. */
  it("does not export the files as entries of their own", () => {
    expect(exportVault(vault()).items.map((i) => i.title)).toEqual(["Chase Bank"]);
  });

  it("says in the file itself that the file is not encrypted", () => {
    expect(exportVault(vault()).warning).toContain("NOT encrypted");
  });

  it("counts what a CSV cannot carry", () => {
    expect(csvOmissions(exportVault(vault()))).toEqual({
      files: 1,
      history: 1,
      customFields: 2,
    });
  });

  it("writes the columns other password managers read", () => {
    const csv = exportCsv(exportVault(vault()));
    const [header, row] = csv.trimEnd().split("\r\n");
    expect(header).toBe('"Group","Title","Username","Password","URL","Notes","TOTP"');
    expect(row).toContain('"Chase Bank"');
    expect(row).toContain('"Banking / Personal"');
    // Custom fields survive in the note rather than as extra columns, which is
    // what makes importers reject a file outright.
    expect(row).toContain("Account number: 00112233");
    expect(row).toContain("Security answer: Rufus");
    // And the file it could not carry is named rather than forgotten.
    expect(row).toContain("[file not included in CSV: passport.png]");
  });

  /**
   * A password starting with `=` is a formula to Excel and Sheets, which will
   * evaluate it: the value on screen stops being the value in the vault, and
   * at worst the spreadsheet fetches a URL built out of somebody's password.
   */
  it("keeps a spreadsheet from executing a password", () => {
    const state = applyOps(emptyVault(), [
      { kind: "keyring.put", opId: "k", ts: at(0), keyringId: "home", name: "Home" },
      {
        kind: "item.put",
        opId: "a",
        ts: at(1),
        itemId: "x",
        keyringId: "home",
        fields: { title: "Odd", password: '=HYPERLINK("http://evil","click")' },
      },
    ]);
    const csv = exportCsv(exportVault(state));
    expect(csv).toContain(`"'=HYPERLINK(""http://evil"",""click"")"`);
  });

  it("escapes a quote and a newline the way every reader expects", () => {
    const state = applyOps(emptyVault(), [
      { kind: "keyring.put", opId: "k", ts: at(0), keyringId: "home", name: "Home" },
      {
        kind: "item.put",
        opId: "a",
        ts: at(1),
        itemId: "x",
        keyringId: "home",
        fields: { title: 'He said "hi"', note: "line one\nline two", password: "p" },
      },
    ]);
    const csv = exportCsv(exportVault(state));
    expect(csv).toContain('"He said ""hi"""');
    expect(csv).toContain('"line one\nline two"');
  });
});
