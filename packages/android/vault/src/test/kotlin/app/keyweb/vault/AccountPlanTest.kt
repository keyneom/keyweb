package app.keyweb.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
 * ambiguity. Parity with the web's `account-plan.test.ts`.
 */
class AccountPlanTest {

    private fun account(issuer: String, name: String, secret: String = "JBSWY3DPEHPK3PXP") =
        ScannedAccount(
            issuer = issuer,
            name = name,
            otp = "otpauth://totp/$issuer:$name?secret=$secret&digits=6&period=30&algorithm=SHA1",
            counterBased = false,
        )

    private fun item(
        id: String,
        title: String,
        username: String = "",
        otp: String = "",
    ) = ItemRecord(
        id = id,
        keyring = Reg("ring", "001700000000000-00000-a"),
        fields = buildMap {
            put("title", Reg(title, "001700000000000-00000-a"))
            if (username.isNotEmpty()) put("username", Reg(username, "001700000000000-00000-a"))
            if (otp.isNotEmpty()) put("otp", Reg(otp, "001700000000000-00000-a"))
        },
        deleted = Reg(false, HLC_ZERO),
    )

    /**
     * The case that found the bug: two accounts at one company, one password
     * in the vault. Neither may claim it on the strength of the company name,
     * because whichever went second would erase whichever went first.
     */
    @Test
    fun `two codes from one company never land on the same password`() {
        val plans = planAccounts(
            listOf(account("Carta", "mika@work.com"), account("Carta", "mika@home.com", "GEZDGNBV")),
            listOf(item("carta", "Carta")),
        )

        assertTrue(plans.all { it.existingItemId == null }, plans.toString())
        assertTrue(plans.all { it.reason == PlanReason.NEW_SEVERAL_FROM_ISSUER })
    }

    /** With a username to tell them apart, each goes to its own password. */
    @Test
    fun `two codes from one company follow their usernames`() {
        val plans = planAccounts(
            listOf(account("Carta", "mika@work.com"), account("Carta", "mika@home.com", "GEZDGNBV")),
            listOf(
                item("work", "Carta", username = "mika@work.com"),
                item("home", "Carta", username = "mika@home.com"),
            ),
        )

        assertEquals(listOf("work", "home"), plans.map { it.existingItemId })
        assertTrue(plans.all { it.reason == PlanReason.ONTO_EXISTING })
    }

    /** One of each is the ordinary case and still works without a username. */
    @Test
    fun `one code and one password still land together`() {
        val plans = planAccounts(
            listOf(account("GitHub", "maria@example.com")),
            listOf(item("gh", "GitHub")),
        )
        assertEquals("gh", plans.single().existingItemId)
        assertEquals(PlanReason.ONTO_EXISTING, plans.single().reason)
    }

    /**
     * Guarantee 2: a password that already carries a second factor keeps it.
     * Replacing one is not recoverable by hand; a spare entry is.
     */
    @Test
    fun `an existing code is never replaced by a different one`() {
        val plans = planAccounts(
            listOf(account("GitHub", "maria@example.com", "GEZDGNBV")),
            listOf(item("gh", "GitHub", otp = "otpauth://totp/GitHub?secret=OLDSECRET")),
        )
        assertNull(plans.single().existingItemId)
        assertEquals(PlanReason.NEW_ALREADY_HAS_A_CODE, plans.single().reason)
        // The name is still carried, so the row can say which password it means.
        assertEquals("GitHub", plans.single().existingTitle)
    }

    /** Rescanning the same export is not a replacement, so it stays harmless. */
    @Test
    fun `the same code again lands where it already is`() {
        val same = account("GitHub", "maria@example.com")
        val plans = planAccounts(listOf(same), listOf(item("gh", "GitHub", otp = same.otp)))
        assertEquals("gh", plans.single().existingItemId)
    }

    /**
     * Guarantee 1 in the case the rules cannot rule out: a vault that already
     * holds two identical passwords. The second claimant becomes new rather
     * than overwriting the first.
     */
    @Test
    fun `a duplicated password in the vault cannot be claimed twice`() {
        val plans = planAccounts(
            listOf(
                account("Carta", "mika@work.com"),
                account("Carta", "mika@work.com", "GEZDGNBV"),
            ),
            listOf(
                item("one", "Carta", username = "mika@work.com"),
                item("two", "Carta", username = "mika@work.com"),
            ),
        )
        val targets = plans.mapNotNull { it.existingItemId }
        assertEquals(targets.size, targets.toSet().size, "two codes claimed one password")
    }

    /**
     * The property behind the guarantee, over every shape above at once: no
     * item id may appear twice in a plan, whatever the inputs were.
     */
    @Test
    fun `no password is ever the target of two codes`() {
        val accounts = listOf(
            account("Carta", "a@x.com"),
            account("Carta", "b@x.com", "GEZDGNBV"),
            account("Carta", ""),
            account("GitHub", "a@x.com"),
            account("", "orphan"),
        )
        val items = listOf(
            item("carta", "Carta", username = "a@x.com"),
            item("carta2", "Carta"),
            item("gh", "GitHub"),
        )
        val targets = planAccounts(accounts, items).mapNotNull { it.existingItemId }
        assertEquals(targets.size, targets.toSet().size, "an item was claimed twice")
    }

    @Test
    fun `a deleted password is not a destination`() {
        val gone = item("gh", "GitHub").copy(deleted = Reg(true, "001700000000001-00000-a"))
        val plans = planAccounts(listOf(account("GitHub", "maria@example.com")), listOf(gone))
        assertNull(plans.single().existingItemId)
        assertEquals(PlanReason.NEW, plans.single().reason)
    }

    @Test
    fun `a code with no issuer becomes a password of its own`() {
        val plans = planAccounts(listOf(account("", "just-a-name")), listOf(item("x", "")))
        assertNull(plans.single().existingItemId)
        assertNotNull(plans.single().account)
    }
}
