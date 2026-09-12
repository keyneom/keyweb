import type { ItemField } from "./model.js";

/**
 * What an item is, and what a screen should ask for.
 *
 * The vault stores flat named fields and merges them one register at a time. A
 * kind adds nothing to that model — it is presentation: which fields to offer,
 * in what order, under what words, and which ones to keep hidden until asked
 * for.
 *
 * Keeping it as presentation rather than schema is what makes it safe to add
 * kinds later. An older client meeting an unknown kind falls back to showing
 * whatever fields the item actually has, which is never wrong, only plainer.
 * Nothing is dropped and nothing fails to parse.
 *
 * The keys themselves are part of the cross-platform contract and must match
 * `Kinds.kt` exactly — a seed phrase written under `seedPhrase` on the phone
 * and `seed_phrase` in the browser would be two fields, and half of someone's
 * wallet backup would appear to vanish depending on where they looked.
 */

export type FieldShape =
  | "text"
  | "secret"
  /** Multi-line, and never masked: a note is read, not copied into a box. */
  | "note"
  /** Numbered word by word, because it is verified against paper. */
  | "phrase"
  | "url"
  | "month"
  /** A live six-digit code rather than the stored secret. */
  | "otp";

export type FieldSpec = {
  key: ItemField;
  label: string;
  shape: FieldShape;
  /** Shown under the input. Plain language, no jargon. */
  hint?: string;
};

export type ItemKind = {
  id: string;
  /** Singular, as it appears on a button: "Add a **credit card**". */
  name: string;
  /** One line explaining what belongs in here, for someone choosing. */
  summary: string;
  fields: FieldSpec[];
};

const TITLE: FieldSpec = { key: "title", label: "Name", shape: "text" };
const NOTE: FieldSpec = {
  key: "note",
  label: "Notes",
  shape: "note",
  hint: "Anything else you want to remember about this.",
};

export const ITEM_KINDS: ItemKind[] = [
  {
    id: "login",
    name: "password",
    summary: "A website or app you sign in to.",
    fields: [
      { ...TITLE, hint: "Whose password is this? For example, your bank's name." },
      { key: "username", label: "Username or email", shape: "text" },
      { key: "password", label: "Password", shape: "secret" },
      {
        key: "otp",
        label: "Six-digit code",
        shape: "otp",
        hint: "If this site sends you a changing code, Keyweb can show it here.",
      },
      { key: "url", label: "Website", shape: "url" },
      NOTE,
    ],
  },
  {
    id: "card",
    name: "card",
    summary: "A credit or debit card.",
    fields: [
      { ...TITLE, hint: "For example, Visa ending 4429." },
      { key: "cardholder", label: "Name on the card", shape: "text" },
      { key: "cardNumber", label: "Card number", shape: "secret" },
      { key: "expiry", label: "Expires", shape: "month", hint: "The MM/YY on the front." },
      { key: "cvv", label: "Security code", shape: "secret", hint: "The 3 digits on the back." },
      { key: "pin", label: "PIN", shape: "secret" },
      NOTE,
    ],
  },
  {
    id: "bank",
    name: "bank account",
    summary: "Account and routing numbers.",
    fields: [
      { ...TITLE, hint: "For example, Joint checking." },
      { key: "bankName", label: "Bank", shape: "text" },
      { key: "accountNumber", label: "Account number", shape: "secret" },
      { key: "routingNumber", label: "Routing number", shape: "text" },
      { key: "iban", label: "IBAN", shape: "text" },
      { key: "swift", label: "SWIFT or BIC", shape: "text" },
      { key: "pin", label: "PIN", shape: "secret" },
      NOTE,
    ],
  },
  {
    id: "wallet",
    name: "crypto wallet",
    summary: "A recovery phrase or private key.",
    fields: [
      { ...TITLE, hint: "For example, Ledger, or MetaMask." },
      {
        key: "seedPhrase",
        label: "Recovery phrase",
        shape: "phrase",
        hint: "The 12 or 24 words. Anyone with these words can move the money.",
      },
      {
        key: "walletPassphrase",
        label: "Extra passphrase",
        shape: "secret",
        hint: "Only if you set one. It is not part of the words.",
      },
      { key: "privateKey", label: "Private key", shape: "secret" },
      { key: "publicAddress", label: "Public address", shape: "text" },
      { key: "network", label: "Network", shape: "text", hint: "For example, Bitcoin or Ethereum." },
      {
        key: "derivationPath",
        label: "Derivation path",
        shape: "text",
        hint: "Only if your wallet showed you one.",
      },
      NOTE,
    ],
  },
  {
    id: "identity",
    name: "ID document",
    summary: "A passport, licence, or national ID.",
    fields: [
      { ...TITLE, hint: "For example, Passport." },
      { key: "fullName", label: "Full name", shape: "text" },
      { key: "documentNumber", label: "Document number", shape: "secret" },
      { key: "issuedBy", label: "Issued by", shape: "text" },
      { key: "expiry", label: "Expires", shape: "month" },
      NOTE,
    ],
  },
  {
    id: "server",
    name: "server or API key",
    summary: "A machine login, key, or token.",
    fields: [
      TITLE,
      { key: "host", label: "Host or address", shape: "text" },
      { key: "username", label: "Username", shape: "text" },
      { key: "password", label: "Password", shape: "secret" },
      { key: "privateKey", label: "Private key", shape: "note", hint: "Paste the whole key." },
      { key: "apiToken", label: "API key or token", shape: "secret" },
      NOTE,
    ],
  },
  {
    id: "licence",
    name: "software licence",
    summary: "A product key.",
    fields: [
      TITLE,
      { key: "product", label: "Product", shape: "text" },
      { key: "licenceKey", label: "Licence key", shape: "secret" },
      { key: "licensedTo", label: "Licensed to", shape: "text" },
      NOTE,
    ],
  },
  {
    id: "note",
    name: "secure note",
    summary: "Anything else you want kept private.",
    fields: [TITLE, { key: "note", label: "Note", shape: "note" }],
  },
];

export const DEFAULT_KIND = "login";

/**
 * The kind an item claims to be.
 *
 * An unrecognised kind resolves to the login template rather than failing,
 * because a client that cannot name the kind can still show every field the
 * item carries.
 */
export function kindOf(id: string | undefined): ItemKind {
  return ITEM_KINDS.find((kind) => kind.id === id) ?? ITEM_KINDS[0]!;
}

/** Every key any built-in kind uses, for telling known fields from custom ones. */
export const KNOWN_KEYS: ReadonlySet<string> = new Set(
  ITEM_KINDS.flatMap((kind) => kind.fields.map((field) => field.key)).concat([
    "kind",
    "folder",
    "tags",
  ]),
);

/**
 * The prefix marking a field someone named themselves as sensitive.
 *
 * Custom fields are free-form, so there is no catalogue to consult for whether
 * one should be masked. The answer travels in the key.
 */
export const SECRET_PREFIX = "secret:";

export function customFieldLabel(key: ItemField): string {
  return key.startsWith(SECRET_PREFIX) ? key.slice(SECRET_PREFIX.length) : key;
}

/** How many words a BIP-39 recovery phrase is allowed to have. */
export const PHRASE_LENGTHS = [12, 15, 18, 21, 24] as const;

export type PhraseCheck = {
  words: string[];
  /** Null when the count is one of the valid lengths. */
  problem: string | null;
};

/**
 * Check a recovery phrase for the mistake that actually destroys money.
 *
 * A phrase with the wrong number of words is not a phrase — it will never
 * restore anything. Catching that at the moment of typing is the difference
 * between a correction and a permanent loss, because the value is usually
 * entered once, from paper, and never verified again.
 *
 * This checks the count, not the words themselves. Validating against the
 * BIP-39 wordlist would also catch a misspelling, and is worth doing; it is not
 * done here yet, so the absence is stated rather than implied.
 */
export function checkPhrase(value: string): PhraseCheck {
  const words = value.trim().toLowerCase().split(/\s+/).filter(Boolean);
  if (words.length === 0) return { words, problem: null };
  if (!(PHRASE_LENGTHS as readonly number[]).includes(words.length)) {
    return {
      words,
      problem:
        `That's ${words.length} word${words.length === 1 ? "" : "s"}. A recovery phrase ` +
        `is usually 12 or 24 — check none are missing before you rely on this.`,
    };
  }
  return { words, problem: null };
}
