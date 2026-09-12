/**
 * Making a password to a site's rules.
 *
 * Sites impose arbitrary and often silly constraints — "at least one number",
 * "no symbols", "exactly 8 to 16 characters" — and the moment a generated
 * password is rejected, people stop using the generator and type their dog's
 * name instead. So the rules are the feature, not the decoration.
 */

export type CharacterSet = "lower" | "upper" | "digits" | "symbols";

export const ALL_SETS: CharacterSet[] = ["lower", "upper", "digits", "symbols"];

/** The default symbol set: broadly accepted, and none of them shell-hostile. */
export const DEFAULT_SYMBOLS = "!@#$%^&*-_=+?";

/**
 * Characters with no shape of their own.
 *
 * Excluding these is offered rather than imposed, because it costs entropy and
 * only helps when a password is going to be read off a screen and typed
 * somewhere else. Colour-coding the display solves the same problem for free,
 * so this is for paper.
 */
const LOOKALIKES = "lI1O0";

const SETS: Record<CharacterSet, string> = {
  lower: "abcdefghijklmnopqrstuvwxyz",
  upper: "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
  digits: "0123456789",
  symbols: DEFAULT_SYMBOLS,
};

export type PasswordRules = {
  length: number;
  /** Sets a character may be drawn from. */
  include: CharacterSet[];
  /** Sets that must each contribute at least one character. */
  require: CharacterSet[];
  /** Overrides the symbol set, for sites that only accept some. */
  symbols?: string;
  avoidLookalikes?: boolean;
};

export type SavedRules = PasswordRules & { id: string; name: string };

export const PRESETS: SavedRules[] = [
  {
    id: "strong",
    name: "Strong",
    length: 20,
    include: ALL_SETS,
    require: ALL_SETS,
  },
  {
    id: "no-symbols",
    name: "Letters and numbers only",
    length: 20,
    include: ["lower", "upper", "digits"],
    require: ["lower", "upper", "digits"],
  },
  {
    id: "easy-to-read",
    name: "Easy to read out loud",
    length: 20,
    include: ["lower", "upper", "digits"],
    require: ["lower", "digits"],
    avoidLookalikes: true,
  },
  {
    id: "old-fashioned",
    name: "Short, for fussy sites",
    length: 12,
    include: ALL_SETS,
    require: ALL_SETS,
    symbols: "!@#$%*",
  },
  {
    id: "pin",
    name: "Numbers only (PIN)",
    length: 6,
    include: ["digits"],
    require: ["digits"],
  },
];

/** The characters a set contributes under these rules. */
export function charactersFor(set: CharacterSet, rules: PasswordRules): string {
  const base = set === "symbols" ? (rules.symbols ?? DEFAULT_SYMBOLS) : SETS[set];
  if (!rules.avoidLookalikes) return base;
  return [...base].filter((character) => !LOOKALIKES.includes(character)).join("");
}

export function alphabetFor(rules: PasswordRules): string {
  const sets = new Set<CharacterSet>([...rules.include, ...rules.require]);
  return [...sets].map((set) => charactersFor(set, rules)).join("");
}

/** Why these rules cannot produce a password, or null when they can. */
export function rulesProblem(rules: PasswordRules): string | null {
  const required = new Set(rules.require);
  if (rules.include.length === 0 && required.size === 0) {
    return "Choose at least one kind of character.";
  }
  for (const set of required) {
    if (charactersFor(set, rules).length === 0) {
      return set === "symbols"
        ? "No symbols are allowed by these rules, so one can't be required."
        : `There are no ${set} characters left to use.`;
    }
  }
  if (rules.length < required.size) {
    return `A password this short can't include one of each. Make it at least ${required.size} characters.`;
  }
  if (rules.length < 4) return "Use at least 4 characters.";
  if (rules.length > 128) return "Keyweb tops out at 128 characters.";
  return null;
}

/**
 * A uniform integer below `bound`, with no modulo bias.
 *
 * The bias from a plain `% bound` over a 32-bit draw is about one part in a
 * hundred million and would never be exploitable. Rejection sampling costs
 * nothing here, though, and removes the question rather than leaving a
 * judgement call in the one function whose whole job is to be unpredictable.
 */
function uniformBelow(bound: number, random: (n: number) => Uint32Array): number {
  const limit = Math.floor(0x1_0000_0000 / bound) * bound;
  for (;;) {
    const draw = random(1)[0]!;
    if (draw < limit) return draw % bound;
  }
}

function defaultRandom(count: number): Uint32Array {
  return crypto.getRandomValues(new Uint32Array(count));
}

/**
 * Generate a password satisfying the rules.
 *
 * Required sets are seeded first, then the rest is drawn from the whole
 * alphabet, then the lot is shuffled. Seeding rather than retrying matters for
 * the awkward cases — "16 characters, must contain a symbol, only `!` and `#`
 * allowed" — where rejection sampling would loop for a long time.
 *
 * The shuffle is a Fisher–Yates over unbiased indices, so no position is more
 * likely to hold a particular category than any other. A generator that always
 * put the digit at the end would leak a character of information to anyone who
 * knew it.
 */
export function generatePassword(
  rules: PasswordRules,
  random: (n: number) => Uint32Array = defaultRandom,
): string {
  const problem = rulesProblem(rules);
  if (problem) throw new Error(problem);

  const alphabet = alphabetFor(rules);
  const characters: string[] = [];

  for (const set of new Set(rules.require)) {
    const pool = charactersFor(set, rules);
    characters.push(pool[uniformBelow(pool.length, random)]!);
  }
  while (characters.length < rules.length) {
    characters.push(alphabet[uniformBelow(alphabet.length, random)]!);
  }

  for (let i = characters.length - 1; i > 0; i -= 1) {
    const j = uniformBelow(i + 1, random);
    [characters[i], characters[j]] = [characters[j]!, characters[i]!];
  }
  return characters.join("");
}

/**
 * How many bits of guessing this actually costs.
 *
 * Not `length × log2(alphabet)`, which is the number people quote and is wrong
 * whenever a set is required: "must contain a symbol" rules out every password
 * that has none, so the space is smaller than the naive product. Counted here
 * by inclusion–exclusion over the required sets, which is exact and cheap for
 * the handful of sets involved.
 */
export function entropyBits(rules: PasswordRules): number {
  if (rulesProblem(rules)) return 0;
  const total = alphabetFor(rules).length;
  const required = [...new Set(rules.require)].map(
    (set) => charactersFor(set, rules).length,
  );

  let count = 0;
  for (let mask = 0; mask < 1 << required.length; mask += 1) {
    let excluded = 0;
    let bits = 0;
    for (let i = 0; i < required.length; i += 1) {
      if (mask & (1 << i)) {
        excluded += required[i]!;
        bits += 1;
      }
    }
    const remaining = total - excluded;
    if (remaining <= 0) continue;
    // Worked in logs, because the counts overflow a double long before the
    // entropy does.
    const term = rules.length * Math.log2(remaining);
    const signed = bits % 2 === 0 ? 1 : -1;
    count += signed * 2 ** (term - rules.length * Math.log2(total));
  }
  return count > 0 ? rules.length * Math.log2(total) + Math.log2(count) : 0;
}

export type Strength = { bits: number; label: string; detail: string };

/**
 * Strength in words rather than a coloured bar.
 *
 * The thresholds are about offline cracking of a stolen password database,
 * which is the scenario a generated password is defending against. Online
 * guessing is rate-limited and irrelevant at any of these sizes.
 */
export function describeStrength(rules: PasswordRules): Strength {
  const bits = entropyBits(rules);
  if (bits < 40) {
    return {
      bits,
      label: "Weak",
      detail: "A determined attacker could work this out. Make it longer.",
    };
  }
  if (bits < 60) {
    return { bits, label: "Fair", detail: "Fine for something unimportant, not for money." };
  }
  if (bits < 80) {
    return { bits, label: "Strong", detail: "Good for almost anything." };
  }
  return {
    bits,
    label: "Very strong",
    detail: "Far beyond what anyone could guess, however long they tried.",
  };
}
