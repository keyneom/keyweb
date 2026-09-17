package app.keyweb.vault

/**
 * Deciding where a scanned second-factor code belongs, before anything is
 * written.
 *
 * ## The failure this exists to prevent
 *
 * An item holds one `otp` field. If two scanned accounts are pointed at the
 * same item, the second write replaces the first — and because the CRDT
 * resolves per field by the later clock, it does so silently and deterministic-
 * ally, with the screen having said a moment earlier that both were going
 * there. Somebody with two accounts at one company would tick two rows, be
 * told both would land on their existing password, and end up with one code.
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
 */
data class AccountPlan(
    val account: ScannedAccount,
    /** The password this code will be added to, or null for a new one. */
    val existingItemId: String?,
    val existingTitle: String?,
    val existingUsername: String?,
    val reason: PlanReason,
)

/**
 * Why a code is going where it is going.
 *
 * Carried so the row can say it *before* the person taps add, rather than
 * leaving them to work it out from the result.
 */
enum class PlanReason {
    /** It belongs to a password already in the vault, unambiguously. */
    ONTO_EXISTING,

    /** No password in the vault looks like this account's. */
    NEW,

    /**
     * More than one code came from this company and nothing distinguishes
     * them, so Keyweb will not pick which password each belongs to.
     */
    NEW_SEVERAL_FROM_ISSUER,

    /** The matching password already carries a different code. */
    NEW_ALREADY_HAS_A_CODE,
}

/**
 * Work out where every scanned account goes, as one decision over the whole
 * batch.
 *
 * One pass over all of them rather than a lookup per account, because two of
 * the three guarantees above are properties of the *set*: whether an item has
 * been claimed already, and whether this company sent more than one code.
 */
fun planAccounts(
    accounts: List<ScannedAccount>,
    items: Collection<ItemRecord>,
): List<AccountPlan> {
    val live = items.filter { !it.deleted.value && !it.isBlob() }
    val fromIssuer = accounts
        .filter { it.issuer.isNotEmpty() }
        .groupingBy { it.issuer.lowercase() }
        .eachCount()
    val claimed = mutableSetOf<String>()

    return accounts.map { account ->
        fun plan(item: ItemRecord?, reason: PlanReason) = AccountPlan(
            account = account,
            existingItemId = item?.id,
            existingTitle = item?.field(Fields.TITLE),
            existingUsername = item?.field(Fields.USERNAME),
            reason = reason,
        )

        if (account.issuer.isEmpty()) return@map plan(null, PlanReason.NEW)

        val sameTitle = live.filter {
            it.field(Fields.TITLE)?.equals(account.issuer, ignoreCase = true) == true
        }

        // The account name is what separates two logins at one company, so it
        // is tried first and is the only thing that can resolve an ambiguity.
        val byUsername = sameTitle.filter {
            account.name.isNotEmpty() &&
                it.field(Fields.USERNAME)?.equals(account.name, ignoreCase = true) == true
        }

        var target = byUsername.singleOrNull()

        if (target == null) {
            // Falling back to the title alone is only safe when it cannot be
            // ambiguous from either direction: one password with this name in
            // the vault, and one code from this company in the scan. The
            // moment there are two Carta codes, neither takes this path.
            val onlyOne = sameTitle.size == 1 &&
                fromIssuer[account.issuer.lowercase()] == 1
            if (onlyOne) target = sameTitle.single()
        }

        if (target != null && claimed.contains(target.id)) {
            // Guarantee 1. Reachable when the vault itself holds two identical
            // passwords, which is not something this code gets to rule out.
            return@map plan(null, PlanReason.NEW_SEVERAL_FROM_ISSUER)
        }

        if (target != null) {
            val already = target.field(Fields.OTP)
            // Guarantee 2. The same code again is not a replacement, so
            // rescanning the same export stays harmless.
            if (!already.isNullOrEmpty() && already != account.otp) {
                return@map plan(target, PlanReason.NEW_ALREADY_HAS_A_CODE)
                    .copy(existingItemId = null)
            }
        }

        if (target == null) {
            val reason = if (sameTitle.isNotEmpty() &&
                (fromIssuer[account.issuer.lowercase()] ?: 0) > 1
            ) {
                PlanReason.NEW_SEVERAL_FROM_ISSUER
            } else {
                PlanReason.NEW
            }
            return@map plan(null, reason)
        }

        claimed += target.id
        plan(target, PlanReason.ONTO_EXISTING)
    }
}
