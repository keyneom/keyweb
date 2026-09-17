import { describe, expect, it } from "vitest";
import { HLC_ZERO, type ItemRecord } from "@keyweb/vault-core";
import { planAccounts } from "../src/vault/account-plan";
import type { ScannedAccount } from "../src/vault/authenticator";

/**
 * Where a scanned second-factor code is allowed to land.
 *
 * The first version of this matched on the issuer alone and took the first
 * password it found, which meant two accounts at one company were both pointed
 * at the same item — and since an item holds one `otp` field, the second write
 * would have replaced the first, silently, after the screen had said a moment
 * earlier that both were going there.
 *
 * So these are stated as the three guarantees rather than as examples: no two
 * codes at one item, no existing code replaced, and no guess where there is an
 * ambiguity. Parity with the Kotlin `AccountPlanTest`.
 */

function account(issuer: string, name: string, secret = "JBSWY3DPEHPK3PXP"): ScannedAccount {
  return {
    issuer,
    name,
    otp: `otpauth://totp/${issuer}:${name}?secret=${secret}&digits=6&period=30&algorithm=SHA1`,
    counterBased: false,
  };
}

function item(id: string, title: string, username = "", otp = ""): ItemRecord {
  const ts = "001700000000000-00000-a";
  const fields: ItemRecord["fields"] = { title: { value: title, ts } };
  if (username) fields.username = { value: username, ts };
  if (otp) fields.otp = { value: otp, ts };
  return {
    id,
    keyring: { value: "ring", ts },
    fields,
    deleted: { value: false, ts: HLC_ZERO },
    history: [],
  };
}

describe("where a scanned code lands", () => {
  /**
   * The case that found the bug: two accounts at one company, one password in
   * the vault. Neither may claim it on the strength of the company name,
   * because whichever went second would erase whichever went first.
   */
  it("never lands two codes from one company on the same password", () => {
    const plans = planAccounts(
      [account("Carta", "mika@work.com"), account("Carta", "mika@home.com", "GEZDGNBV")],
      [item("carta", "Carta")],
    );
    expect(plans.every((p) => p.existingItemId === null)).toBe(true);
    expect(plans.every((p) => p.reason === "new-several-from-issuer")).toBe(true);
  });

  it("follows the usernames when there are two of each", () => {
    const plans = planAccounts(
      [account("Carta", "mika@work.com"), account("Carta", "mika@home.com", "GEZDGNBV")],
      [item("work", "Carta", "mika@work.com"), item("home", "Carta", "mika@home.com")],
    );
    expect(plans.map((p) => p.existingItemId)).toEqual(["work", "home"]);
    expect(plans.every((p) => p.reason === "onto-existing")).toBe(true);
  });

  it("still lands one code on the one password it matches", () => {
    const plans = planAccounts([account("GitHub", "maria@example.com")], [item("gh", "GitHub")]);
    expect(plans[0]!.existingItemId).toBe("gh");
    expect(plans[0]!.reason).toBe("onto-existing");
  });

  /**
   * Guarantee 2: a password that already carries a second factor keeps it.
   * Replacing one is not recoverable by hand; a spare entry is.
   */
  it("never replaces an existing code with a different one", () => {
    const plans = planAccounts(
      [account("GitHub", "maria@example.com", "GEZDGNBV")],
      [item("gh", "GitHub", "", "otpauth://totp/GitHub?secret=OLDSECRET")],
    );
    expect(plans[0]!.existingItemId).toBeNull();
    expect(plans[0]!.reason).toBe("new-already-has-a-code");
    // The name is still carried, so the row can say which password it means.
    expect(plans[0]!.existingTitle).toBe("GitHub");
  });

  it("treats the same code again as where it already is", () => {
    const same = account("GitHub", "maria@example.com");
    const plans = planAccounts([same], [item("gh", "GitHub", "", same.otp)]);
    expect(plans[0]!.existingItemId).toBe("gh");
  });

  /**
   * Guarantee 1 in the case the rules cannot rule out: a vault that already
   * holds two identical passwords.
   */
  it("cannot claim a duplicated password twice", () => {
    const plans = planAccounts(
      [account("Carta", "mika@work.com"), account("Carta", "mika@work.com", "GEZDGNBV")],
      [item("one", "Carta", "mika@work.com"), item("two", "Carta", "mika@work.com")],
    );
    const targets = plans.map((p) => p.existingItemId).filter((id): id is string => id !== null);
    expect(new Set(targets).size).toBe(targets.length);
  });

  /** The property behind the guarantee, over every shape above at once. */
  it("never makes one password the target of two codes", () => {
    const plans = planAccounts(
      [
        account("Carta", "a@x.com"),
        account("Carta", "b@x.com", "GEZDGNBV"),
        account("Carta", ""),
        account("GitHub", "a@x.com"),
        account("", "orphan"),
      ],
      [item("carta", "Carta", "a@x.com"), item("carta2", "Carta"), item("gh", "GitHub")],
    );
    const targets = plans.map((p) => p.existingItemId).filter((id): id is string => id !== null);
    expect(new Set(targets).size).toBe(targets.length);
  });

  it("does not offer a deleted password as a destination", () => {
    const gone = item("gh", "GitHub");
    gone.deleted = { value: true, ts: "001700000000001-00000-a" };
    const plans = planAccounts([account("GitHub", "maria@example.com")], [gone]);
    expect(plans[0]!.existingItemId).toBeNull();
    expect(plans[0]!.reason).toBe("new");
  });

  it("makes a code with no issuer a password of its own", () => {
    const plans = planAccounts([account("", "just-a-name")], [item("x", "")]);
    expect(plans[0]!.existingItemId).toBeNull();
  });
});
