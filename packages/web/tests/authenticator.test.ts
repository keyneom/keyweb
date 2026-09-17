import { describe, expect, it } from "vitest";
import {
  encodeBase32,
  NotAnAccountCode,
  readAccountCode,
} from "../src/vault/authenticator";

/**
 * Reading Google Authenticator's export.
 *
 * The payloads are built here rather than pasted from a real export, because a
 * real one is somebody's actual second factors. The encoder below is the
 * inverse of the decoder under test and nothing else uses it, so the anchor
 * that keeps the pair honest is the base32 vector: `Hello!\xde\xad\xbe\xef` is
 * the secret every authenticator's own documentation uses, and it encodes to
 * `JBSWY3DPEHPK3PXP`.
 */

const SECRET = new Uint8Array([0x48, 0x65, 0x6c, 0x6c, 0x6f, 0x21, 0xde, 0xad, 0xbe, 0xef]);

function varint(value: number): number[] {
  const out: number[] = [];
  let n = value;
  do {
    let byte = n & 0x7f;
    n >>>= 7;
    if (n > 0) byte |= 0x80;
    out.push(byte);
  } while (n > 0);
  return out;
}

function lengthDelimited(field: number, bytes: Uint8Array | number[]): number[] {
  const body = Array.from(bytes);
  return [...varint((field << 3) | 2), ...varint(body.length), ...body];
}

function value(field: number, n: number): number[] {
  return [...varint(field << 3), ...varint(n)];
}

function account(options: {
  secret?: Uint8Array;
  name?: string;
  issuer?: string;
  algorithm?: number;
  digits?: number;
  type?: number;
}): number[] {
  const text = (s: string) => Array.from(new TextEncoder().encode(s));
  return [
    ...lengthDelimited(1, options.secret ?? SECRET),
    ...lengthDelimited(2, text(options.name ?? "maria@example.com")),
    ...lengthDelimited(3, text(options.issuer ?? "GitHub")),
    ...value(4, options.algorithm ?? 1),
    ...value(5, options.digits ?? 1),
    ...value(6, options.type ?? 2),
  ];
}

function migration(accounts: number[][], batch?: { index: number; total: number; id: number }) {
  const body = [
    ...accounts.flatMap((a) => lengthDelimited(1, a)),
    ...value(2, 1),
    ...value(3, batch?.total ?? 1),
    ...value(4, batch?.index ?? 0),
    ...value(5, batch?.id ?? 7),
  ];
  const base64 = btoa(String.fromCharCode(...body));
  return `otpauth-migration://offline?data=${encodeURIComponent(base64)}`;
}

describe("what an authenticator's QR code holds", () => {
  it("encodes a secret the way every authenticator shows it", () => {
    expect(encodeBase32(SECRET)).toBe("JBSWY3DPEHPK3PXP");
  });

  it("reads one account out of an export", () => {
    const batch = readAccountCode(migration([account({})]));
    expect(batch.accounts).toHaveLength(1);
    const [only] = batch.accounts;
    expect(only!.issuer).toBe("GitHub");
    expect(only!.name).toBe("maria@example.com");
    expect(only!.otp).toContain("secret=JBSWY3DPEHPK3PXP");
    expect(only!.otp).toContain("issuer=GitHub");
    expect(only!.otp).toContain("period=30");
    expect(only!.counterBased).toBe(false);
  });

  it("reads every account in one code, not just the first", () => {
    const batch = readAccountCode(
      migration([
        account({ issuer: "GitHub" }),
        account({ issuer: "Fastmail" }),
        account({ issuer: "Bank" }),
      ]),
    );
    expect(batch.accounts.map((a) => a.issuer)).toEqual(["GitHub", "Fastmail", "Bank"]);
  });

  /**
   * The failure that costs people accounts: a large export is split across
   * several QR codes and nothing on screen says so, so people scan the first,
   * see ten of their thirty, and assume that was all of it.
   */
  it("reports which code of how many it just read", () => {
    const batch = readAccountCode(
      migration([account({})], { index: 1, total: 3, id: 42 }),
    );
    expect(batch.index).toBe(1);
    expect(batch.total).toBe(3);
    expect(batch.batchId).toBe(42);
  });

  it("carries eight-digit and SHA-256 accounts across as they are", () => {
    const batch = readAccountCode(migration([account({ algorithm: 2, digits: 2 })]));
    expect(batch.accounts[0]!.otp).toContain("digits=8");
    expect(batch.accounts[0]!.otp).toContain("algorithm=SHA256");
  });

  /**
   * Keyweb cannot generate a counter-based code — the counter advances on
   * every use, so the vault would be written every time somebody looked and
   * two devices would immediately disagree about where it is. It still comes
   * across, flagged, rather than disappearing without a word.
   */
  it("flags a counter-based account rather than dropping it", () => {
    const batch = readAccountCode(migration([account({ type: 1 })]));
    expect(batch.accounts[0]!.counterBased).toBe(true);
    expect(batch.accounts[0]!.otp).toContain("JBSWY3DPEHPK3PXP");
  });

  /** The other thing a camera sees: one account, straight from a website. */
  it("reads a plain otpauth code too", () => {
    const batch = readAccountCode(
      "otpauth://totp/GitHub:maria@example.com?secret=JBSWY3DPEHPK3PXP&issuer=GitHub",
    );
    expect(batch.total).toBe(1);
    expect(batch.accounts[0]!.issuer).toBe("GitHub");
    expect(batch.accounts[0]!.otp).toContain("JBSWY3DPEHPK3PXP");
  });

  it("says so plainly when the code is something else", () => {
    expect(() => readAccountCode("https://example.com")).toThrow(NotAnAccountCode);
    expect(() => readAccountCode("otpauth-migration://offline?data=not-base64!!")).toThrow(
      NotAnAccountCode,
    );
    // Truncated: a length prefix that runs off the end of the payload must not
    // be read as whatever happens to be in memory after it.
    expect(() => readAccountCode("otpauth-migration://offline?data=CgQKAgE")).toThrow(
      NotAnAccountCode,
    );
  });

  /** A newer export with a field this build has never heard of still works. */
  it("skips fields it does not know", () => {
    const withExtra = [...account({}), ...value(99, 12345)];
    const batch = readAccountCode(migration([withExtra]));
    expect(batch.accounts[0]!.issuer).toBe("GitHub");
  });
});
