import { itemField, type ItemRecord } from "@keyweb/vault-core";
import { isBlobItem } from "@keyweb/vault-core";
import type { ScannedAccount } from "./authenticator";

/**
 * Deciding where a scanned second-factor code belongs, before anything is
 * written.
 *
 * ## The failure this exists to prevent
 *
 * An item holds one `otp` field. If two scanned accounts are pointed at the
 * same item, the second write replaces the first — and because the CRDT
 * resolves per field by the later clock, it does so silently and
 * deterministically, with the screen having said a moment earlier that both
 * were going there. Somebody with two accounts at one company would tick two
 * rows, be told both would land on their existing password, and end up with
 * one code.
 *
 * The first version matched on the issuer alone and did exactly that. So this
 * file exists, and the rules below are stated as guarantees rather than as a
 * heuristic that usually works.
 *
 * ## The guarantees
 *
 *  1. **No two scanned accounts are ever pointed at the same item.** Enforced
 *     by construction here, not by hoping the rules never collide.
 *  2. **An existing code is never replaced by a different one.** A password
 *     that already carries a second factor keeps it; the scanned one becomes a
 *     password of its own rather than quietly taking its place.
 *  3. **Ambiguity becomes a new password, never a guess.** Two codes for the
 *     same company with nothing to tell them apart is exactly when guessing is
 *     worst, so that is where guessing stops.
 *
 * Nothing here is lossy: the fallback in every case is a new password, which
 * the person can move or merge afterwards. Losing a second factor cannot be
 * undone by hand, and a spare entry can.
 *
 * The mirror of the Kotlin `AccountPlan.kt`, down to the tests.
 */

/**
 * Why a code is going where it is going.
 *
 * Carried so the row can say it *before* the person taps add, rather than
 * leaving them to work it out from the result.
 */
export type PlanReason =
  /** It belongs to a password already in the vault, unambiguously. */
  | "onto-existing"
  /** No password in the vault looks like this account's. */
  | "new"
  /**
   * More than one code came from this company and nothing distinguishes them,
   * so Keyweb will not pick which password each belongs to.
   */
  | "new-several-from-issuer"
  /** The matching password already carries a different code. */
  | "new-already-has-a-code";

export type AccountPlan = {
  account: ScannedAccount;
  /** The password this code will be added to, or null for a new one. */
  existingItemId: string | null;
  existingTitle: string | null;
  existingUsername: string | null;
  reason: PlanReason;
};

/**
 * Work out where every scanned account goes, as one decision over the batch.
 *
 * One pass over all of them rather than a lookup per account, because two of
 * the three guarantees above are properties of the *set*: whether an item has
 * been claimed already, and whether this company sent more than one code.
 */
export function planAccounts(
  accounts: ScannedAccount[],
  items: Iterable<ItemRecord>,
): AccountPlan[] {
  const live = [...items].filter((item) => !item.deleted.value && !isBlobItem(item));

  const fromIssuer = new Map<string, number>();
  for (const account of accounts) {
    if (account.issuer === "") continue;
    const key = account.issuer.toLowerCase();
    fromIssuer.set(key, (fromIssuer.get(key) ?? 0) + 1);
  }

  const claimed = new Set<string>();
  const same = (a: string | undefined, b: string) =>
    a !== undefined && a.toLowerCase() === b.toLowerCase();

  return accounts.map((account): AccountPlan => {
    const describe = (item: ItemRecord | null, reason: PlanReason): AccountPlan => ({
      account,
      existingItemId: item?.id ?? null,
      existingTitle: item ? (itemField(item, "title") ?? null) : null,
      existingUsername: item ? (itemField(item, "username") ?? null) : null,
      reason,
    });

    if (account.issuer === "") return describe(null, "new");

    const sameTitle = live.filter((item) => same(itemField(item, "title"), account.issuer));

    // The account name is what separates two logins at one company, so it is
    // tried first and is the only thing that can resolve an ambiguity.
    const byUsername = sameTitle.filter(
      (item) => account.name !== "" && same(itemField(item, "username"), account.name),
    );

    let target: ItemRecord | null = byUsername.length === 1 ? byUsername[0]! : null;

    if (target === null) {
      // Falling back to the title alone is only safe when it cannot be
      // ambiguous from either direction: one password with this name in the
      // vault, and one code from this company in the scan. The moment there
      // are two Carta codes, neither takes this path.
      const only =
        sameTitle.length === 1 && fromIssuer.get(account.issuer.toLowerCase()) === 1;
      if (only) target = sameTitle[0]!;
    }

    if (target !== null && claimed.has(target.id)) {
      // Guarantee 1. Reachable when the vault itself holds two identical
      // passwords, which is not something this code gets to rule out.
      return describe(null, "new-several-from-issuer");
    }

    if (target !== null) {
      const already = itemField(target, "otp");
      // Guarantee 2. The same code again is not a replacement, so rescanning
      // the same export stays harmless.
      if (already !== undefined && already !== "" && already !== account.otp) {
        return { ...describe(target, "new-already-has-a-code"), existingItemId: null };
      }
    }

    if (target === null) {
      const several =
        sameTitle.length > 0 && (fromIssuer.get(account.issuer.toLowerCase()) ?? 0) > 1;
      return describe(null, several ? "new-several-from-issuer" : "new");
    }

    claimed.add(target.id);
    return describe(target, "onto-existing");
  });
}
