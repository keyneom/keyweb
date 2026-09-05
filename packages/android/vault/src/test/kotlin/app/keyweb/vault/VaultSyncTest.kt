package app.keyweb.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private class Harness(
    val remote: FakeRemote = FakeRemote(),
    node: String = "A",
    physical: (() -> Long)? = null,
    val storage: VaultStorage = MemoryVaultStorage(),
) {
    val clock = if (physical == null) Clock(node) else Clock(node, physical)
    val sync = VaultSync(storage, remote, clock, newId = seqIds(node))
}

private suspend fun Harness.seed() {
    sync.putKeyring(keyringId = "ring", name = "Household")
    sync.putItem(
        itemId = "chase",
        keyringId = "ring",
        fields = mapOf(ItemField.TITLE to "Chase Bank", ItemField.PASSWORD to "old-password"),
    )
    sync.sync()
}

class EditDuringSyncTest {

    @Test
    fun `an edit made while a sync is in flight survives and reaches the cloud`() = runTest {
        val h = Harness()
        h.seed()

        // The user opens the app, an automatic sync starts, and while the upload
        // is in flight they save a new bank password and are shown "Saved".
        h.remote.writeDelayMs = 20
        var injected = false
        h.remote.onBeforeWrite = {
            if (!injected) {
                injected = true
                h.sync.putItem(
                    itemId = "bank",
                    keyringId = "ring",
                    fields = mapOf(ItemField.PASSWORD to "the-one-that-matters"),
                )
            }
        }

        h.sync.putItem(
            itemId = "chase",
            keyringId = "ring",
            fields = mapOf(ItemField.PASSWORD to "rotated"),
        )
        h.sync.sync()

        // It must still be on the device.
        val local = h.sync.state()
        assertEquals("the-one-that-matters", local.items["bank"]?.field(ItemField.PASSWORD))

        // It must NOT have been acknowledged: it was not in that revision.
        assertEquals(1, h.storage.pending().size)
        assertFalse(h.sync.isFullyBackedUp())

        // And the very next sync must publish it.
        h.sync.sync()
        assertEquals(
            "the-one-that-matters",
            h.remote.snapshot().items["bank"]?.field(ItemField.PASSWORD),
        )
        assertTrue(h.sync.isFullyBackedUp())
    }

    @Test
    fun `every one of many edits during a single slow sync is kept`() = runTest {
        val h = Harness()
        h.seed()

        h.remote.writeDelayMs = 30
        var injected = false
        h.remote.onBeforeWrite = {
            if (!injected) {
                injected = true
                repeat(25) { i ->
                    h.sync.putItem(
                        itemId = "site-$i",
                        keyringId = "ring",
                        fields = mapOf(ItemField.PASSWORD to "pw-$i"),
                    )
                }
            }
        }

        h.sync.putItem("chase", "ring", mapOf(ItemField.PASSWORD to "rotated"))
        h.sync.sync()
        h.sync.sync()

        val published = h.remote.snapshot()
        repeat(25) { i ->
            assertEquals("pw-$i", published.items["site-$i"]?.field(ItemField.PASSWORD))
        }
        assertTrue(h.sync.isFullyBackedUp())
    }
}

class OfflineTest {

    @Test
    fun `reports offline, keeps the queue, and publishes everything on reconnect`() = runTest {
        val h = Harness()
        h.seed()

        h.remote.offline = true
        h.sync.putItem("bank", "ring", mapOf(ItemField.PASSWORD to "offline-save"))
        val outcome = h.sync.sync()
        assertTrue(outcome is SyncOutcome.Offline)
        assertEquals(1, outcome.pending)
        assertFalse(h.sync.isFullyBackedUp())

        // Durable on the device even though the cloud never saw it.
        assertEquals("offline-save", h.sync.state().items["bank"]?.field(ItemField.PASSWORD))

        h.remote.offline = false
        val reconnected = h.sync.sync()
        assertTrue(reconnected is SyncOutcome.Published)
        assertEquals(0, h.storage.pending().size)
        assertEquals(
            "offline-save",
            h.remote.snapshot().items["bank"]?.field(ItemField.PASSWORD),
        )
    }
}

class TwoDevicesTest {

    @Test
    fun `merges both sides when a competing revision lands mid-flight`() = runTest {
        val remote = FakeRemote()
        val a = Harness(remote, node = "A")
        val b = Harness(remote, node = "B")

        a.seed()
        b.sync.sync() // B adopts the baseline

        a.sync.putItem("netflix", "ring", mapOf(ItemField.PASSWORD to "from-A"))
        b.sync.putItem("costco", "ring", mapOf(ItemField.PASSWORD to "from-B"))

        // B publishes during A's read-to-write window, so A's write is rejected
        // and must re-merge rather than clobber.
        var raced = false
        remote.onBeforeWrite = {
            if (!raced) {
                raced = true
                b.sync.sync()
            }
        }

        val outcome = a.sync.sync()
        assertTrue(outcome is SyncOutcome.Published)
        assertTrue(remote.rejectedWrites > 0)

        val published = remote.snapshot()
        assertEquals("from-A", published.items["netflix"]?.field(ItemField.PASSWORD))
        assertEquals("from-B", published.items["costco"]?.field(ItemField.PASSWORD))
    }

    @Test
    fun `keeps both edits when each device changes a different field of one login`() = runTest {
        val remote = FakeRemote()
        val a = Harness(remote, node = "A")
        val b = Harness(remote, node = "B")

        a.seed()
        b.sync.sync()

        a.sync.putItem("chase", "ring", mapOf(ItemField.USERNAME to "maria@x.com"))
        b.sync.putItem("chase", "ring", mapOf(ItemField.PASSWORD to "rotated-by-B"))

        a.sync.sync()
        b.sync.sync()
        a.sync.sync()

        val item = assertNotNull(remote.snapshot().items["chase"])
        assertEquals("maria@x.com", item.field(ItemField.USERNAME))
        assertEquals("rotated-by-B", item.field(ItemField.PASSWORD))
    }

    @Test
    fun `gives up cleanly rather than looping forever under permanent contention`() = runTest {
        val h = Harness()
        h.seed()

        h.sync.putItem("bank", "ring", mapOf(ItemField.PASSWORD to "x"))
        h.remote.forceConflicts = 99
        val outcome = h.sync.sync()
        assertTrue(outcome is SyncOutcome.ConflictExhausted)
        // The edit is still queued: nothing was thrown away.
        assertEquals(1, outcome.pending)
    }
}

class CrashSafetyTest {

    /** Storage whose acknowledgement fails once, simulating a kill between the
     *  successful upload and the local bookkeeping that follows it. */
    private class BrittleAck(private val inner: MemoryVaultStorage) : VaultStorage by inner {
        var failNext = true
        override suspend fun ack(opIds: List<String>) {
            if (failNext) {
                failNext = false
                throw IllegalStateException("process killed")
            }
            inner.ack(opIds)
        }
    }

    @Test
    fun `replays an operation published but not acknowledged, without duplicating it`() = runTest {
        val inner = MemoryVaultStorage()
        val brittle = BrittleAck(inner)
        val remote = FakeRemote()
        val sync = VaultSync(brittle, remote, Clock("A"), newId = seqIds("A"))

        sync.putKeyring(keyringId = "ring", name = "Household")
        sync.putItem("bank", "ring", mapOf(ItemField.PASSWORD to "s3cret"))

        assertFailsWith<IllegalStateException> { sync.sync() }

        // The upload did land, and the operations are still queued.
        assertNotNull(remote.snapshot().items["bank"])
        assertEquals(2, inner.pending().size)

        // Replaying is harmless and settles the queue.
        val outcome = sync.sync()
        assertTrue(outcome is SyncOutcome.Unchanged)
        assertEquals(0, inner.pending().size)
        assertEquals(1, visibleItems(remote.snapshot()).size)
        assertEquals("s3cret", remote.snapshot().items["bank"]?.field(ItemField.PASSWORD))
    }

    @Test
    fun `rebuilds correctly after a restart with a non-empty queue`() = runTest {
        val storage = MemoryVaultStorage()
        val remote = FakeRemote()
        val sync = VaultSync(storage, remote, Clock("A"), newId = seqIds("A"))

        sync.putKeyring(keyringId = "ring", name = "Household")
        remote.offline = true
        sync.putItem("bank", "ring", mapOf(ItemField.PASSWORD to "s3cret"))
        sync.sync()

        // Restart: durable state and queue survive, engine and clock are new.
        val restarted = storage.fork()
        val resumed = Clock("A", resume = restarted.readClock())
        val sync2 = VaultSync(restarted, remote, resumed, newId = seqIds("A2"))

        remote.offline = false
        val outcome = sync2.sync()
        assertTrue(outcome is SyncOutcome.Published)
        assertEquals("s3cret", remote.snapshot().items["bank"]?.field(ItemField.PASSWORD))
        assertEquals(0, restarted.pending().size)
    }
}

class ClockSkewTest {

    @Test
    fun `a device with a slow clock still wins for its later edit`() = runTest {
        val remote = FakeRemote()
        // B's clock is an hour behind A's, common on old hardware.
        val a = Harness(remote, node = "A", physical = { 1_700_000_000_000 })
        val b = Harness(remote, node = "B", physical = { 1_700_000_000_000 - 3_600_000 })

        a.sync.putKeyring(keyringId = "ring", name = "Household")
        a.sync.putItem("bank", "ring", mapOf(ItemField.PASSWORD to "from-A"))
        a.sync.sync()

        // B pulls A's revision, then makes a genuinely later edit.
        b.sync.sync()
        b.sync.putItem("bank", "ring", mapOf(ItemField.PASSWORD to "from-B"))
        b.sync.sync()

        // With naive wall-clock LWW, B's edit would lose and vanish.
        assertEquals("from-B", remote.snapshot().items["bank"]?.field(ItemField.PASSWORD))

        a.sync.sync()
        assertEquals("from-B", a.sync.state().items["bank"]?.field(ItemField.PASSWORD))
    }
}

class BackedUpReportingTest {

    @Test
    fun `never claims backed up while anything is queued`() = runTest {
        val h = Harness()

        h.sync.putKeyring(keyringId = "ring", name = "Household")
        assertFalse(h.sync.isFullyBackedUp())

        h.sync.sync()
        assertTrue(h.sync.isFullyBackedUp())

        h.remote.offline = true
        h.sync.putItem("bank", "ring", mapOf(ItemField.PASSWORD to "x"))
        assertFalse(h.sync.isFullyBackedUp())
        h.sync.sync()
        assertFalse(h.sync.isFullyBackedUp())

        h.remote.offline = false
        h.sync.sync()
        assertTrue(h.sync.isFullyBackedUp())
    }
}

class StorageContractTest {

    @Test
    fun `applyRemote joins rather than replaces`() = runTest {
        val h = Harness()
        h.sync.putKeyring(keyringId = "ring", name = "Household")
        h.sync.putItem("local-only", "ring", mapOf(ItemField.PASSWORD to "keep-me"))

        // An incoming revision that knows nothing about the local item must not
        // erase it. A blind assignment here is the easy-bc bug.
        val joined = h.storage.applyRemote(emptyVault())
        assertEquals("keep-me", joined.items["local-only"]?.field(ItemField.PASSWORD))
    }
}
