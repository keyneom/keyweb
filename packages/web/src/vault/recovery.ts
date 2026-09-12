/**
 * The recovery code.
 *
 * The vault key is derived from a passkey. That is excellent until the passkey
 * is gone — a lost Google account, a passkey that was device-bound rather than
 * synced — at which point the encrypted backup is undecryptable by anyone,
 * including us. There is deliberately no support path: no Keyweb server holds
 * a key, so nobody can be phoned to get a vault back.
 *
 * So the backup is sealed twice: once under the passkey-derived key, and once
 * under a key derived from a code the user keeps on paper. Both envelopes carry
 * the same vault and are written together, so the recovery copy can never go
 * stale. Either path opens it; losing both is the only unrecoverable case, and
 * the app says so plainly rather than implying a rescue that does not exist.
 *
 * Wrapping the vault key under a second key would be the tidier construction,
 * but sync-kit makes content keys non-extractable, so there is nothing to wrap.
 * Encrypting twice reaches the same place using only the library's own
 * primitives.
 */

/**
 * Crockford base32: no I, L, O or U, so there is no confusing 1 with l, 0 with
 * O, and no accidental profanity. Decoding accepts lower case and maps the
 * look-alikes, because someone reading a printed sheet will make exactly those
 * mistakes.
 */
const ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
const RECOVERY_BYTES = 15; // 120 bits, encoding to exactly 24 characters.
const GROUP = 4;

export function generateRecoverySecret(): Uint8Array {
  return crypto.getRandomValues(new Uint8Array(RECOVERY_BYTES));
}

/** `H7K2-9MNP-...`, six groups of four. */
export function formatRecoveryCode(secret: Uint8Array): string {
  let bits = 0;
  let value = 0;
  let out = "";
  for (const byte of secret) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      out += ALPHABET[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) out += ALPHABET[(value << (5 - bits)) & 31];
  return out.match(new RegExp(`.{1,${GROUP}}`, "g"))?.join("-") ?? out;
}

export class InvalidRecoveryCode extends Error {
  constructor(message = "That recovery code isn't right. Check it and try again.") {
    super(message);
    this.name = "InvalidRecoveryCode";
  }
}

export function parseRecoveryCode(code: string): Uint8Array {
  const cleaned = code
    .toUpperCase()
    .replace(/[\s-]/g, "")
    // The look-alikes people actually type.
    .replace(/[IL]/g, "1")
    .replace(/O/g, "0");
  if (cleaned.length === 0) throw new InvalidRecoveryCode();

  let bits = 0;
  let value = 0;
  const bytes: number[] = [];
  for (const char of cleaned) {
    const index = ALPHABET.indexOf(char);
    if (index < 0) throw new InvalidRecoveryCode();
    value = (value << 5) | index;
    bits += 5;
    if (bits >= 8) {
      bytes.push((value >>> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  if (bytes.length !== RECOVERY_BYTES) {
    throw new InvalidRecoveryCode(
      `A recovery code is ${RECOVERY_BYTES * 8 / 5 | 0} characters. That one has ${cleaned.length}.`,
    );
  }
  return new Uint8Array(bytes);
}
