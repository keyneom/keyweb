package app.keyweb.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val clock = Clock("test")
private var opCounter = 0
private fun nextId() = "op-${++opCounter}"

private fun put(
    itemId: String,
    fields: Map<ItemField, String>,
    keyringId: String = "ring",
): VaultOp = VaultOp.ItemPut(nextId(), clock.now(), itemId, keyringId, fields)

private fun build(ops: List<VaultOp>): VaultState = applyOps(emptyVault(), ops)

class HlcTest {

    @Test
    fun `round-trips and sorts lexicographically in causal order`() {
        val a = encodeHlc(HlcParts(1_700_000_000_000, 0, "a"))
        val b = encodeHlc(HlcParts(1_700_000_000_000, 1, "a"))
        val c = encodeHlc(HlcParts(1_700_000_000_001, 0, "a"))
        assertEquals(HlcParts(1_700_000_000_000, 1, "a"), decodeHlc(b))
        assertTrue(a < b)
        assertTrue(b < c)
        assertEquals(listOf(a, b, c), listOf(c, a, b).sorted())
        assertTrue(HLC_ZERO < a)
    }

    @Test
    fun `advances monotonically even when the wall clock stalls or goes backwards`() {
        var wall = 1_000L
        val c = Clock("n", { wall })
        val first = c.now()
        val second = c.now()
        wall = 500 // clock corrected backwards
        val third = c.now()
        assertTrue(second > first)
        assertTrue(third > second)
    }

    @Test
    fun `sorts a local edit after a remote one it has observed`() {
        val slow = Clock("slow", { 1_000 })
        val fast = Clock("fast", { 9_999_999 })
        val remote = fast.now()
        slow.observe(remote)
        assertTrue(slow.now() > remote)
    }

    @Test
    fun `resumes causal time across a restart`() {
        val before = Clock("n", { 5_000 })
        val stamp = before.now()
        // Process restarts and the device clock has been set backwards.
        val after = Clock("n", { 100 }, resume = before.snapshot())
        assertTrue(after.now() > stamp)
    }
}

class MergeLawsTest {
    private val a = build(listOf(put("one", mapOf(ItemField.TITLE to "One")), put("two", mapOf(ItemField.TITLE to "Two"))))
    private val b = build(listOf(put("two", mapOf(ItemField.TITLE to "Two edited")), put("three", mapOf(ItemField.TITLE to "Three"))))

    @Test
    fun `is idempotent`() {
        assertEquals(fingerprint(a), fingerprint(mergeVaults(a, a)))
    }

    @Test
    fun `is commutative`() {
        assertEquals(fingerprint(mergeVaults(a, b)), fingerprint(mergeVaults(b, a)))
    }

    @Test
    fun `is associative`() {
        val c = build(listOf(put("four", mapOf(ItemField.TITLE to "Four"))))
        assertEquals(
            fingerprint(mergeVaults(mergeVaults(a, b), c)),
            fingerprint(mergeVaults(a, mergeVaults(b, c))),
        )
    }

    @Test
    fun `keeps every item from both sides`() {
        assertEquals(listOf("one", "three", "two"), mergeVaults(a, b).items.keys.sorted())
    }
}

class OpsTest {

    @Test
    fun `resolves per field, so simultaneous edits to one login both survive`() {
        val base = build(
            listOf(
                put(
                    "chase",
                    mapOf(
                        ItemField.TITLE to "Chase",
                        ItemField.USERNAME to "old",
                        ItemField.PASSWORD to "old",
                    ),
                ),
            ),
        )
        val withUser = applyOp(base, put("chase", mapOf(ItemField.USERNAME to "new-user")))
        val withPass = applyOp(base, put("chase", mapOf(ItemField.PASSWORD to "new-pass")))
        val merged = mergeVaults(withUser, withPass)
        val item = assertNotNull(merged.items["chase"])
        assertEquals("new-user", item.field(ItemField.USERNAME))
        assertEquals("new-pass", item.field(ItemField.PASSWORD))
        assertEquals("Chase", item.field(ItemField.TITLE))
    }

    @Test
    fun `is idempotent when the same operation is replayed`() {
        val op = put("chase", mapOf(ItemField.PASSWORD to "abc"))
        val once = applyOp(emptyVault(), op)
        val twice = applyOp(once, op)
        assertEquals(fingerprint(once), fingerprint(twice))
    }

    @Test
    fun `a later edit beats an earlier delete, and a later delete beats an earlier edit`() {
        val base = build(listOf(put("a", mapOf(ItemField.TITLE to "A")), put("b", mapOf(ItemField.TITLE to "B"))))
        val afterDelete = applyOp(base, VaultOp.ItemDelete(nextId(), clock.now(), "a"))
        val revived = applyOp(afterDelete, put("a", mapOf(ItemField.TITLE to "A again")))
        assertEquals(false, revived.items["a"]?.deleted?.value)

        val gone = applyOp(revived, VaultOp.ItemDelete(nextId(), clock.now(), "b"))
        assertEquals(true, gone.items["b"]?.deleted?.value)
    }

    @Test
    fun `hides items whose keyring was deleted without destroying them`() {
        var state = build(listOf(put("x", mapOf(ItemField.TITLE to "X"))))
        state = applyOp(state, VaultOp.KeyringPut(nextId(), clock.now(), "ring", "Household"))
        assertEquals(1, visibleItems(state).size)
        state = applyOp(state, VaultOp.KeyringDelete(nextId(), clock.now(), "ring"))
        assertEquals(0, visibleItems(state).size)
        assertNotNull(state.items["x"])
    }

    @Test
    fun `retains a superseded password so an accidental overwrite is recoverable`() {
        var state = build(listOf(put("chase", mapOf(ItemField.PASSWORD to "original"))))
        state = applyOp(state, put("chase", mapOf(ItemField.PASSWORD to "overwritten")))
        assertEquals("overwritten", state.items["chase"]?.field(ItemField.PASSWORD))
        val previous = state.items["chase"]?.history?.firstOrNull { it.field == ItemField.PASSWORD }
        assertEquals("original", previous?.value)
    }

    @Test
    fun `caps retained history so a long-lived item cannot grow without bound`() {
        var state = emptyVault()
        repeat(40) { i -> state = applyOp(state, put("chase", mapOf(ItemField.PASSWORD to "pw-$i"))) }
        val item = assertNotNull(state.items["chase"])
        assertTrue(item.history.size <= HISTORY_LIMIT)
        assertEquals("pw-39", item.field(ItemField.PASSWORD))
    }
}

class FingerprintTest {

    @Test
    fun `changes when a register advances`() {
        val before = build(listOf(put("a", mapOf(ItemField.TITLE to "A"))))
        val after = applyOp(before, put("a", mapOf(ItemField.TITLE to "A2")))
        assertNotEquals(fingerprint(before), fingerprint(after))
    }

    @Test
    fun `does not depend on insertion order`() {
        val one = build(listOf(put("a", mapOf(ItemField.TITLE to "A"))))
        val two = build(listOf(put("b", mapOf(ItemField.TITLE to "B"))))
        assertEquals(fingerprint(mergeVaults(one, two)), fingerprint(mergeVaults(two, one)))
    }
}
