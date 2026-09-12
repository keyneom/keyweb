import { describe, expect, it } from "vitest";
import {
  decodeBase32,
  formatOtp,
  groupCode,
  InvalidOtpSecret,
  parseOtp,
  secondsRemaining,
  totpAt,
  type OtpConfig,
} from "../src/vault/totp";

/**
 * RFC 6238 Appendix B.
 *
 * The published vectors are the only thing that can actually settle whether
 * this is right. A TOTP implementation that is subtly wrong — off-by-one on the
 * counter, truncating from the wrong offset — still produces six plausible
 * digits, and would fail silently at a sign-in screen with no way for anyone to
 * tell why.
 *
 * The seeds are the RFC's ASCII strings, base32-encoded.
 */
const SEED_SHA1 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"; // "12345678901234567890"
const SEED_SHA256 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZA====";
const SEED_SHA512 =
  "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" +
  "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNA=";

function config(over: Partial<OtpConfig> = {}): OtpConfig {
  return { secret: SEED_SHA1, digits: 8, period: 30, algorithm: "SHA-1", ...over };
}

describe("RFC 6238 test vectors", () => {
  const cases: [number, OtpConfig, string][] = [
    [59, config(), "94287082"],
    [1111111109, config(), "07081804"],
    [1111111111, config(), "14050471"],
    [1234567890, config(), "89005924"],
    [2000000000, config(), "69279037"],
    [20000000000, config(), "65353130"],

    [59, config({ secret: SEED_SHA256, algorithm: "SHA-256" }), "46119246"],
    [1111111109, config({ secret: SEED_SHA256, algorithm: "SHA-256" }), "68084774"],
    [20000000000, config({ secret: SEED_SHA256, algorithm: "SHA-256" }), "77737706"],

    [59, config({ secret: SEED_SHA512, algorithm: "SHA-512" }), "90693936"],
    [1111111109, config({ secret: SEED_SHA512, algorithm: "SHA-512" }), "25091201"],
    [20000000000, config({ secret: SEED_SHA512, algorithm: "SHA-512" }), "47863826"],
  ];

  for (const [seconds, options, expected] of cases) {
    it(`${options.algorithm} at ${seconds}s produces ${expected}`, async () => {
      expect(await totpAt(options, seconds * 1000)).toBe(expected);
    });
  }

  it("crosses the 32-bit counter boundary correctly", async () => {
    // 20000000000s is past where a naive 32-bit shift silently wraps, which is
    // why the RFC includes it. Worth naming: the failure would appear years
    // from now, not in testing.
    expect(await totpAt(config(), 20000000000 * 1000)).toBe("65353130");
  });
});

describe("six-digit codes, as sites actually issue them", () => {
  it("truncates to the requested number of digits", async () => {
    const six = await totpAt(config({ digits: 6 }), 59_000);
    expect(six).toBe("287082");
    expect(six).toHaveLength(6);
  });

  it("pads a short code rather than dropping a leading zero", async () => {
    // A code of "012345" typed as "12345" is rejected by the site, and the
    // person retyping it has no idea why.
    const padded = String(1).padStart(6, "0");
    expect(padded).toBe("000001");
    const code = await totpAt(config({ digits: 6 }), 1111111109_000);
    expect(code).toMatch(/^\d{6}$/);
  });

  it("holds a code steady across its window and changes at the boundary", async () => {
    const options = config({ digits: 6, period: 30 });
    const atStart = await totpAt(options, 60_000);
    const nearEnd = await totpAt(options, 89_999);
    const after = await totpAt(options, 90_000);
    expect(nearEnd).toBe(atStart);
    expect(after).not.toBe(atStart);
  });

  it("counts down to the moment the code changes", () => {
    const options = config({ period: 30 });
    expect(secondsRemaining(options, 60_000)).toBe(30);
    expect(secondsRemaining(options, 75_000)).toBe(15);
    expect(secondsRemaining(options, 89_000)).toBe(1);
  });
});

describe("taking in a key the way a site hands it over", () => {
  it("accepts a bare secret, spaces and all", () => {
    const parsed = parseOtp("gezd gnbv gy3t qojq gezd gnbv gy3t qojq");
    expect(parsed.secret).toBe(SEED_SHA1);
    expect(parsed.digits).toBe(6);
    expect(parsed.period).toBe(30);
  });

  it("accepts the otpauth URI behind a QR code", () => {
    const parsed = parseOtp(
      `otpauth://totp/GitHub:maria%40example.com?secret=${SEED_SHA1}&issuer=GitHub&digits=6&period=30`,
    );
    expect(parsed.secret).toBe(SEED_SHA1);
    expect(parsed.issuer).toBe("GitHub");
    expect(parsed.label).toBe("maria@example.com");
  });

  it("carries a non-standard period and digit count", () => {
    const parsed = parseOtp(
      `otpauth://totp/Bank?secret=${SEED_SHA1}&digits=8&period=60&algorithm=SHA256`,
    );
    expect(parsed.digits).toBe(8);
    expect(parsed.period).toBe(60);
    expect(parsed.algorithm).toBe("SHA-256");
  });

  it("round-trips through the single stored field", () => {
    const original = parseOtp(
      `otpauth://totp/Bank:me?secret=${SEED_SHA1}&digits=8&period=60&algorithm=SHA256&issuer=Bank`,
    );
    const again = parseOtp(formatOtp(original));
    expect(again.secret).toBe(original.secret);
    expect(again.digits).toBe(original.digits);
    expect(again.period).toBe(original.period);
    expect(again.algorithm).toBe(original.algorithm);
  });

  it("rejects a mistyped key rather than producing codes nothing accepts", () => {
    // Silently accepting this is the cruel failure: the app shows confident
    // six-digit codes and the site refuses every one of them.
    expect(() => parseOtp("not a real key!!")).toThrow(InvalidOtpSecret);
    expect(() => parseOtp("")).toThrow(InvalidOtpSecret);
    expect(() => decodeBase32("0189")).toThrow(InvalidOtpSecret);
  });

  it("says plainly when a key is counter-based rather than time-based", () => {
    expect(() => parseOtp(`otpauth://hotp/Bank?secret=${SEED_SHA1}&counter=1`)).toThrow(
      /counter-based/,
    );
  });
});

describe("reading a code out loud", () => {
  it("splits into halves", () => {
    expect(groupCode("123456")).toBe("123 456");
    expect(groupCode("12345678")).toBe("1234 5678");
  });
});
