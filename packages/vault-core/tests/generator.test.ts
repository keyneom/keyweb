import { describe, expect, it } from "vitest";
import {
  ALL_SETS,
  alphabetFor,
  charactersFor,
  describeStrength,
  entropyBits,
  generatePassword,
  PRESETS,
  rulesProblem,
  type PasswordRules,
} from "../src/generator.js";

const strong: PasswordRules = { length: 20, include: ALL_SETS, require: ALL_SETS };

function has(value: string, pattern: RegExp): boolean {
  return pattern.test(value);
}

describe("generating to a site's rules", () => {
  it("honours the requested length exactly", () => {
    for (const length of [4, 8, 12, 20, 64, 128]) {
      expect(generatePassword({ ...strong, length })).toHaveLength(length);
    }
  });

  it("always includes one of every required kind", () => {
    // The point of the whole feature: a password the site rejects sends people
    // back to typing their dog's name.
    for (let i = 0; i < 300; i += 1) {
      const value = generatePassword({ ...strong, length: 8 });
      expect(has(value, /[a-z]/)).toBe(true);
      expect(has(value, /[A-Z]/)).toBe(true);
      expect(has(value, /[0-9]/)).toBe(true);
      expect(has(value, /[!@#$%^&*\-_=+?]/)).toBe(true);
    }
  });

  it("never includes a kind that was excluded", () => {
    const rules: PasswordRules = {
      length: 24,
      include: ["lower", "digits"],
      require: ["digits"],
    };
    for (let i = 0; i < 200; i += 1) {
      const value = generatePassword(rules);
      expect(has(value, /[A-Z]/)).toBe(false);
      expect(has(value, /[^a-z0-9]/)).toBe(false);
    }
  });

  it("respects a restricted symbol set", () => {
    // Some sites accept only a handful of symbols, and reject the rest without
    // saying which.
    const rules: PasswordRules = {
      length: 16,
      include: ALL_SETS,
      require: ["symbols"],
      symbols: "!#",
    };
    for (let i = 0; i < 200; i += 1) {
      const value = generatePassword(rules);
      expect(has(value, /[!#]/)).toBe(true);
      expect(has(value, /[@$%^&*\-_=+?]/)).toBe(false);
    }
  });

  it("drops look-alike characters when asked", () => {
    const rules: PasswordRules = { ...strong, length: 64, avoidLookalikes: true };
    for (let i = 0; i < 50; i += 1) {
      expect(generatePassword(rules)).not.toMatch(/[lI1O0]/);
    }
  });

  it("spreads the required characters across the whole password", () => {
    // Seeding required sets first and shuffling afterwards is what avoids this.
    // A generator that always put the digit last would hand an attacker a
    // character for free.
    const positions = new Set<number>();
    for (let i = 0; i < 400; i += 1) {
      const value = generatePassword({ ...strong, length: 8 });
      positions.add(value.search(/[0-9]/));
    }
    // Every position should turn up, not just the tail.
    expect(positions.size).toBeGreaterThanOrEqual(7);
  });

  it("does not favour any character over another", () => {
    // A crude uniformity check. With 26 letters over 26k draws, a generator
    // that was materially skewed would show up well outside this band.
    const rules: PasswordRules = { length: 26, include: ["lower"], require: [] };
    const counts = new Map<string, number>();
    for (let i = 0; i < 1000; i += 1) {
      for (const character of generatePassword(rules)) {
        counts.set(character, (counts.get(character) ?? 0) + 1);
      }
    }
    expect(counts.size).toBe(26);
    const expected = 26000 / 26;
    for (const count of counts.values()) {
      expect(Math.abs(count - expected) / expected).toBeLessThan(0.2);
    }
  });

  it("every preset produces something matching its own rules", () => {
    for (const preset of PRESETS) {
      expect(rulesProblem(preset)).toBeNull();
      const value = generatePassword(preset);
      expect(value).toHaveLength(preset.length);
      for (const set of preset.require) {
        const pool = charactersFor(set, preset);
        expect([...value].some((character) => pool.includes(character))).toBe(true);
      }
    }
  });
});

describe("rules that cannot work", () => {
  it("refuses rather than quietly producing something weaker", () => {
    // Silently dropping a requirement is the dangerous failure: the password
    // looks fine and the site rejects it, or worse, accepts a weaker one.
    expect(rulesProblem({ length: 20, include: [], require: [] })).toMatch(/at least one kind/);
    expect(rulesProblem({ length: 2, include: ALL_SETS, require: ALL_SETS })).toMatch(/short/);
    expect(rulesProblem({ length: 200, include: ALL_SETS, require: [] })).toMatch(/128/);
    expect(
      rulesProblem({ length: 16, include: ALL_SETS, require: ["symbols"], symbols: "" }),
    ).toMatch(/No symbols/);
  });

  it("throws rather than returning a password that breaks the rules", () => {
    expect(() => generatePassword({ length: 2, include: ALL_SETS, require: ALL_SETS })).toThrow();
  });

  it("treats a required set as included even if the caller forgot", () => {
    const rules: PasswordRules = { length: 16, include: ["lower"], require: ["digits"] };
    expect(rulesProblem(rules)).toBeNull();
    expect(alphabetFor(rules)).toMatch(/0123456789/);
  });
});

describe("saying how strong it is", () => {
  it("counts the requirement as the constraint it is", () => {
    // "Must contain a symbol" rules out every password that has none, so the
    // real space is smaller than length x log2(alphabet). Quoting the naive
    // product overstates the strength, which is the wrong way to be wrong.
    const naive = 8 * Math.log2(alphabetFor(strong).length);
    const actual = entropyBits({ ...strong, length: 8 });
    expect(actual).toBeLessThan(naive);
    expect(actual).toBeGreaterThan(naive - 2);
  });

  it("matches the simple calculation when nothing is required", () => {
    const rules: PasswordRules = { length: 20, include: ALL_SETS, require: [] };
    expect(entropyBits(rules)).toBeCloseTo(20 * Math.log2(alphabetFor(rules).length), 6);
  });

  it("grows with length and shrinks with a smaller alphabet", () => {
    expect(entropyBits({ ...strong, length: 30 })).toBeGreaterThan(entropyBits(strong));
    expect(
      entropyBits({ length: 20, include: ["digits"], require: ["digits"] }),
    ).toBeLessThan(entropyBits(strong));
  });

  it("describes a six-digit PIN as weak and a long password as very strong", () => {
    const pin = PRESETS.find((preset) => preset.id === "pin")!;
    expect(describeStrength(pin).label).toBe("Weak");
    expect(describeStrength(strong).label).toBe("Very strong");
  });
});
