import { describe, expect, it } from "vitest";
import { generatePassword } from "../src/screens/ItemEdit";

describe("password generator", () => {
  it("produces readable groups of the requested shape", () => {
    const value = generatePassword(4, 4);
    expect(value.split("-")).toHaveLength(4);
    for (const group of value.split("-")) expect(group).toHaveLength(4);
  });

  it("omits characters that are misread when spoken or copied by hand", () => {
    const joined = Array.from({ length: 200 }, () => generatePassword()).join("");
    // The three pairs people actually confuse: l/I/1, O/0, and B/8. Lowercase
    // b stays, since it is not mistaken for an 8.
    expect(joined).not.toMatch(/[lI1O0B]/);
  });

  it("does not repeat itself", () => {
    const seen = new Set(Array.from({ length: 500 }, () => generatePassword()));
    expect(seen.size).toBe(500);
  });
});
