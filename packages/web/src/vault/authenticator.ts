import { formatOtp, InvalidOtpSecret, parseOtp, type OtpConfig } from "./totp";

/**
 * Reading what an authenticator app puts in a QR code.
 *
 * Two shapes turn up, and a person holding a phone cannot tell them apart:
 *
 *  - `otpauth://totp/...` — one account, what a website shows when it sets up
 *    two-factor authentication;
 *  - `otpauth-migration://offline?data=...` — Google Authenticator's "Transfer
 *    accounts" export, which is a protobuf holding *many* accounts and, when
 *    there are more than a handful, is split across several QR codes.
 *
 * Both are handled here because the person scanning does not care which they
 * have. Nothing else in the app should have to know the difference either.
 *
 * ## Why the protobuf is decoded by hand
 *
 * It is four field numbers in one message and seven in another, and the wire
 * format is a varint and a length prefix. A code generator and a runtime would
 * be more machinery than the thing they parse, in a module whose whole job is
 * to read bytes from a stranger's camera — the smaller the surface, the better.
 */

/** One account read out of a QR code, before anybody decides where to put it. */
export type ScannedAccount = {
  /** The service, as the authenticator recorded it: "GitHub", "Google". */
  issuer: string;
  /** The account at that service, usually an email address. */
  name: string;
  /** The seed, as the `otpauth://` URI Keyweb stores in its `otp` field. */
  otp: string;
  /**
   * A counter-based code rather than a time-based one.
   *
   * Rare, and Keyweb cannot generate them: a HOTP counter advances every time
   * a code is used, which means the vault would have to be written on every
   * glance and two devices would immediately disagree about where the counter
   * is. Carried through and flagged rather than dropped, so the person is told
   * rather than left to find out when the code does not work.
   */
  counterBased: boolean;
};

/** What one QR code turned out to hold. */
export type ScannedBatch = {
  accounts: ScannedAccount[];
  /**
   * Which of how many, for an export split across several codes.
   *
   * Google Authenticator splits a large export and gives no indication on
   * screen that it has done so — people scan the first code, see ten of their
   * thirty accounts, and assume that is all there was. The numbers are carried
   * so the app can say "code 1 of 3" and keep asking.
   */
  index: number;
  total: number;
  /** Ties the codes of one export together, so two exports cannot be mixed. */
  batchId: number;
};

export class NotAnAccountCode extends Error {
  constructor(message = "That QR code isn't an authenticator code.") {
    super(message);
    this.name = "NotAnAccountCode";
  }
}

export function readAccountCode(text: string): ScannedBatch {
  const trimmed = text.trim();
  if (trimmed.toLowerCase().startsWith("otpauth-migration://")) {
    return readMigration(trimmed);
  }
  if (trimmed.toLowerCase().startsWith("otpauth://")) {
    let config: OtpConfig;
    try {
      config = parseOtp(trimmed);
    } catch (cause) {
      if (cause instanceof InvalidOtpSecret) throw new NotAnAccountCode();
      throw cause;
    }
    return {
      accounts: [
        {
          issuer: config.issuer ?? "",
          name: config.label ?? "",
          otp: formatOtp(config),
          counterBased: trimmed.toLowerCase().startsWith("otpauth://hotp/"),
        },
      ],
      index: 0,
      total: 1,
      batchId: 0,
    };
  }
  throw new NotAnAccountCode();
}

function readMigration(uri: string): ScannedBatch {
  let data: string | null;
  try {
    data = new URL(uri).searchParams.get("data");
  } catch {
    throw new NotAnAccountCode();
  }
  if (!data) throw new NotAnAccountCode();

  let bytes: Uint8Array;
  try {
    // The QR payload is standard base64, and the URL decoding above has
    // already turned `%2B` back into `+`.
    const binary = atob(data);
    bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0));
  } catch {
    throw new NotAnAccountCode();
  }

  const accounts: ScannedAccount[] = [];
  let index = 0;
  let total = 1;
  let batchId = 0;

  for (const field of fields(bytes)) {
    switch (field.number) {
      case 1:
        if (field.bytes) {
          const account = readAccount(field.bytes);
          if (account) accounts.push(account);
        }
        break;
      case 3:
        total = Number(field.value ?? 1n) || 1;
        break;
      case 4:
        index = Number(field.value ?? 0n);
        break;
      case 5:
        batchId = Number(field.value ?? 0n);
        break;
    }
  }

  if (accounts.length === 0) throw new NotAnAccountCode();
  return { accounts, index, total, batchId };
}

function readAccount(bytes: Uint8Array): ScannedAccount | null {
  let secret: Uint8Array | null = null;
  let name = "";
  let issuer = "";
  let algorithm: OtpConfig["algorithm"] = "SHA-1";
  let digits = 6;
  let counterBased = false;

  for (const field of fields(bytes)) {
    switch (field.number) {
      case 1:
        secret = field.bytes ?? null;
        break;
      case 2:
        name = text(field.bytes);
        break;
      case 3:
        issuer = text(field.bytes);
        break;
      case 4:
        // 1 = SHA1, 2 = SHA256, 3 = SHA512. 4 is MD5, which no site uses and
        // Keyweb cannot compute; it falls through to SHA-1 rather than
        // failing the whole batch for one account.
        algorithm = field.value === 2n ? "SHA-256" : field.value === 3n ? "SHA-512" : "SHA-1";
        break;
      case 5:
        // An enum, not the number of digits: 1 means six, 2 means eight.
        digits = field.value === 2n ? 8 : 6;
        break;
      case 6:
        counterBased = field.value === 1n;
        break;
    }
  }

  if (!secret || secret.length === 0) return null;

  return {
    issuer,
    name,
    otp: formatOtp({
      secret: encodeBase32(secret),
      digits,
      // The export carries no period, because Google Authenticator only ever
      // uses thirty seconds. Written out rather than left to a default, so the
      // stored field says what it means.
      period: 30,
      algorithm,
      label: name,
      issuer: issuer || undefined,
    }),
    counterBased,
  };
}

/** RFC 4648 base32, which is the alphabet every authenticator issues. */
const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

export function encodeBase32(bytes: Uint8Array): string {
  let bits = 0;
  let accumulator = 0;
  let out = "";
  for (const byte of bytes) {
    accumulator = (accumulator << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      out += ALPHABET[(accumulator >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) out += ALPHABET[(accumulator << (5 - bits)) & 31];
  // Unpadded: `parseOtp` strips padding anyway, and every authenticator shows
  // these secrets unpadded when it shows them at all.
  return out;
}

type Field = { number: number; value?: bigint; bytes?: Uint8Array };

/**
 * Walks a protobuf message, yielding only what this file knows how to want.
 *
 * Unknown field numbers and unknown wire types are skipped rather than
 * rejected — that is what protobuf compatibility means, and a newer export
 * with one extra field must not cost somebody their accounts.
 */
function* fields(bytes: Uint8Array): Generator<Field> {
  let at = 0;
  while (at < bytes.length) {
    const [key, afterKey] = varint(bytes, at);
    at = afterKey;
    const number = Number(key >> 3n);
    const wire = Number(key & 7n);
    switch (wire) {
      case 0: {
        const [value, next] = varint(bytes, at);
        at = next;
        yield { number, value };
        break;
      }
      case 2: {
        const [length, next] = varint(bytes, at);
        const end = next + Number(length);
        if (end > bytes.length) throw new NotAnAccountCode();
        yield { number, bytes: bytes.subarray(next, end) };
        at = end;
        break;
      }
      case 5:
        at += 4;
        break;
      case 1:
        at += 8;
        break;
      default:
        throw new NotAnAccountCode();
    }
    if (at > bytes.length) throw new NotAnAccountCode();
  }
}

function varint(bytes: Uint8Array, start: number): [bigint, number] {
  let value = 0n;
  let shift = 0n;
  let at = start;
  while (at < bytes.length) {
    const byte = bytes[at]!;
    value |= BigInt(byte & 0x7f) << shift;
    at += 1;
    if ((byte & 0x80) === 0) return [value, at];
    shift += 7n;
    // Ten bytes is the most a 64-bit varint can occupy; more means the bytes
    // are not what they claim to be.
    if (shift > 63n) throw new NotAnAccountCode();
  }
  throw new NotAnAccountCode();
}

function text(bytes: Uint8Array | undefined): string {
  return bytes ? new TextDecoder().decode(bytes) : "";
}
