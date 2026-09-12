/**
 * Time-based one-time passwords (RFC 6238).
 *
 * ## Why this lives in the password manager
 *
 * The usual objection is that keeping the password and its second factor in one
 * app collapses two factors into one. That is worth taking seriously, and it is
 * narrower than it sounds.
 *
 * What two-factor authentication actually defends against, in practice, is
 * *remote* compromise of a credential: a reused password, a breached database,
 * a phishing page. None of those yield the TOTP secret, which never leaves this
 * vault and is never typed into a site. An attacker holding the password still
 * cannot produce a code.
 *
 * What is genuinely lost is independence under *vault* compromise — but someone
 * who can open the vault already has every password in it, so the second factor
 * was not going to save that account either.
 *
 * The comparison that matters is not "separate app" versus "same app". It is
 * "second factor" versus "no second factor", because the realistic alternative
 * for the person this app is built for is SMS, or nothing at all.
 *
 * TOTP is still phishable — a code typed into a convincing fake works for
 * thirty seconds. Passkeys are not, and are the better answer where a site
 * offers them.
 */

/** RFC 4648 base32, as every authenticator issues it. Not Crockford. */
const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

export class InvalidOtpSecret extends Error {
  constructor(message = "That doesn't look like a valid authentication key.") {
    super(message);
    this.name = "InvalidOtpSecret";
  }
}

export type OtpConfig = {
  secret: string;
  digits: number;
  period: number;
  algorithm: "SHA-1" | "SHA-256" | "SHA-512";
  /** Shown beside the code so it is obvious which account it belongs to. */
  label?: string;
  issuer?: string;
};

export function decodeBase32(value: string): Uint8Array {
  const cleaned = value.toUpperCase().replace(/[\s-]/g, "").replace(/=+$/, "");
  if (cleaned.length === 0) throw new InvalidOtpSecret();

  let bits = 0;
  let accumulator = 0;
  const bytes: number[] = [];
  for (const character of cleaned) {
    const index = ALPHABET.indexOf(character);
    if (index < 0) throw new InvalidOtpSecret();
    accumulator = (accumulator << 5) | index;
    bits += 5;
    if (bits >= 8) {
      bytes.push((accumulator >>> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  if (bytes.length === 0) throw new InvalidOtpSecret();
  return new Uint8Array(bytes);
}

/**
 * Accepts what people actually have to hand.
 *
 * Sites offer either a QR code, whose payload is an `otpauth://` URI, or "can't
 * scan it?" text that is the bare base32 secret — often with spaces in it.
 * Both are pasted here, so both are understood.
 */
export function parseOtp(input: string): OtpConfig {
  const trimmed = input.trim();
  if (!trimmed) throw new InvalidOtpSecret();

  if (!/^otpauth:\/\//i.test(trimmed)) {
    // A bare secret. Validate by decoding: a typo must fail now, not silently
    // produce codes that no site accepts.
    decodeBase32(trimmed);
    return { secret: normalizeSecret(trimmed), digits: 6, period: 30, algorithm: "SHA-1" };
  }

  let url: URL;
  try {
    url = new URL(trimmed);
  } catch {
    throw new InvalidOtpSecret();
  }
  if (url.host.toLowerCase() === "hotp") {
    throw new InvalidOtpSecret(
      "That's a counter-based key, which Keyweb can't show yet. Ask the site for the time-based one.",
    );
  }

  const secret = url.searchParams.get("secret");
  if (!secret) throw new InvalidOtpSecret();
  decodeBase32(secret);

  const digits = Number(url.searchParams.get("digits") ?? 6);
  const period = Number(url.searchParams.get("period") ?? 30);
  const algorithm = ({
    SHA1: "SHA-1",
    SHA256: "SHA-256",
    SHA512: "SHA-512",
  } as const)[(url.searchParams.get("algorithm") ?? "SHA1").toUpperCase()] ?? "SHA-1";

  // The path is "/Issuer:account" or "/account".
  const path = decodeURIComponent(url.pathname.replace(/^\//, ""));
  const [maybeIssuer, ...rest] = path.split(":");
  const issuer = url.searchParams.get("issuer") ?? (rest.length > 0 ? maybeIssuer : undefined);
  const label = rest.length > 0 ? rest.join(":").trim() : path;

  return {
    secret: normalizeSecret(secret),
    digits: Number.isFinite(digits) && digits >= 6 && digits <= 10 ? digits : 6,
    period: Number.isFinite(period) && period > 0 ? period : 30,
    algorithm,
    ...(issuer ? { issuer } : {}),
    ...(label ? { label } : {}),
  };
}

function normalizeSecret(value: string): string {
  return value.toUpperCase().replace(/[\s-]/g, "").replace(/=+$/, "");
}

/**
 * Round-trips a config through the single stored field.
 *
 * Stored as an `otpauth://` URI even when the site gave a bare secret, so the
 * digit count and period travel with it and one field carries everything.
 */
export function formatOtp(config: OtpConfig): string {
  const label = config.issuer
    ? `${config.issuer}:${config.label ?? ""}`
    : (config.label ?? "Keyweb");
  const params = new URLSearchParams({
    secret: config.secret,
    digits: String(config.digits),
    period: String(config.period),
    algorithm: config.algorithm.replace("-", ""),
  });
  if (config.issuer) params.set("issuer", config.issuer);
  return `otpauth://totp/${encodeURIComponent(label)}?${params}`;
}

/** The code for a given moment, and how long it lasts. */
export async function totpAt(config: OtpConfig, atMs: number): Promise<string> {
  const counter = Math.floor(atMs / 1000 / config.period);

  // An 8-byte big-endian counter. Bit shifts are 32-bit in JS, so the high
  // word is computed by division rather than by shifting.
  const message = new Uint8Array(8);
  let high = Math.floor(counter / 0x100000000);
  let low = counter >>> 0;
  for (let i = 7; i >= 4; i -= 1) {
    message[i] = low & 0xff;
    low = low >>> 8;
  }
  for (let i = 3; i >= 0; i -= 1) {
    message[i] = high & 0xff;
    high = Math.floor(high / 256);
  }

  const key = await crypto.subtle.importKey(
    "raw",
    decodeBase32(config.secret) as unknown as ArrayBuffer,
    { name: "HMAC", hash: config.algorithm },
    false,
    ["sign"],
  );
  const mac = new Uint8Array(await crypto.subtle.sign("HMAC", key, message as unknown as ArrayBuffer));

  // Dynamic truncation, RFC 4226 §5.4.
  const offset = mac[mac.length - 1]! & 0x0f;
  const binary =
    ((mac[offset]! & 0x7f) << 24) |
    ((mac[offset + 1]! & 0xff) << 16) |
    ((mac[offset + 2]! & 0xff) << 8) |
    (mac[offset + 3]! & 0xff);

  return String(binary % 10 ** config.digits).padStart(config.digits, "0");
}

/** Seconds until the current code stops working. */
export function secondsRemaining(config: OtpConfig, atMs: number): number {
  return config.period - Math.floor(atMs / 1000) % config.period;
}

/** `123 456` — grouped, because it is read aloud and typed under time pressure. */
export function groupCode(code: string): string {
  const half = Math.ceil(code.length / 2);
  return `${code.slice(0, half)} ${code.slice(half)}`;
}
