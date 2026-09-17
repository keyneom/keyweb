package app.keyweb.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What an item used to hold has been kept since the CRDT was written and shown
 * nowhere, on either platform.
 *
 * That was survivable while it was only a Keyweb edit somebody had just made.
 * It stopped being survivable when the KeePass import started replaying years
 * of earlier passwords into the same place: carried across, synced, backed up,
 * and impossible to look at — which is data loss with extra steps, because the
 * person deletes the original file believing they have it.
 *
 * Parity with the web's `history.test.ts`.
 */
class HistoryTest {

    private fun at(n: Int): Hlc =
        encodeHlc(HlcParts(wall = 1_700_000_000_000L + n, counter = 0, node = "test"))

    private fun put(n: Int, fields: Map<ItemField, String>) =
        VaultOp.ItemPut(
            opId = "op-$n",
            ts = at(n),
            itemId = "bank",
            keyringId = "ring",
            fields = fields,
        )

    private val ring = VaultOp.KeyringPut("k", at(0), "ring", "Home")

    @Test
    fun `reports each superseded value, newest first`() {
        val state = applyOps(
            emptyVault(),
            listOf(
                ring,
                put(1, mapOf("title" to "Bank", "password" to "first")),
                put(2, mapOf("password" to "second")),
                put(3, mapOf("password" to "third")),
            ),
        )

        val past = state.items.getValue("bank").pastValues()
        assertEquals(listOf("second", "first"), past.map { it.value })
        assertEquals("third", state.items.getValue("bank").fields["password"]?.value)
        // Masked on the same rule as the live field, so an old password is not
        // printed on screen just because it is old.
        assertTrue(past.all { it.secret })
        assertEquals("password", past.first().label)
    }

    /** The name its owner gave it, not the key it is stored under. */
    @Test
    fun `uses the field's own name`() {
        val state = applyOps(
            emptyVault(),
            listOf(
                ring,
                put(1, mapOf("title" to "Bank", "secret:Backup PIN" to "1111")),
                put(2, mapOf("secret:Backup PIN" to "2222")),
            ),
        )
        val first = state.items.getValue("bank").pastValues().first()
        assertEquals("Backup PIN", first.label)
        assertEquals("1111", first.value)
        assertTrue(first.secret)
    }

    /**
     * "This used to be nothing" is not something anybody came here to read,
     * and a removed field writes a blank on purpose — so the blank is the
     * *new* value and the real one is what lands in history.
     */
    @Test
    fun `leaves out values that were blank`() {
        val state = applyOps(
            emptyVault(),
            listOf(
                ring,
                put(1, mapOf("title" to "Bank", "Account number" to "")),
                put(2, mapOf("Account number" to "00112233")),
                put(3, mapOf("Account number" to "")),
            ),
        )
        assertEquals(
            listOf("00112233"),
            state.items.getValue("bank").pastValues().map { it.value },
        )
    }

    /** Restoring is an ordinary write, so the way back is never a one-way door. */
    @Test
    fun `keeps a way back after a restore`() {
        var state = applyOps(
            emptyVault(),
            listOf(
                ring,
                put(1, mapOf("title" to "Bank", "password" to "old")),
                put(2, mapOf("password" to "new")),
            ),
        )
        state = applyOps(state, listOf(put(3, mapOf("password" to "old"))))

        assertEquals("old", state.items.getValue("bank").fields["password"]?.value)
        assertEquals(
            listOf("new", "old"),
            state.items.getValue("bank").pastValues().map { it.value },
        )
    }
}
