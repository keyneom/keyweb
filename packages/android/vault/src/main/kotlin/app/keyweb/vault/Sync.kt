package app.keyweb.vault

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SyncOutcome {
    val pending: Int

    data class Published(val version: String, override val pending: Int) : SyncOutcome
    data class Unchanged(override val pending: Int) : SyncOutcome
    data class Offline(override val pending: Int, val reason: String) : SyncOutcome
    data class ConflictExhausted(override val pending: Int) : SyncOutcome
}

data class SyncStatus(
    /** Edits saved locally but not yet proven present in a published revision. */
    val pending: Int = 0,
    val lastPublishedAtMs: Long? = null,
    val lastError: String? = null,
    val syncing: Boolean = false,
)

/**
 * Orchestrates local edits and encrypted backup.
 *
 * The guarantee: once [commit] returns, the edit is durable locally and will
 * reach the published revision, whatever happens next — a sync already in
 * flight, a lost connection, a competing device, or the process being killed.
 *
 * Three rules produce it, and all three matter:
 *
 *  1. An edit is written to durable storage and the outbox *before* commit
 *     returns. The UI's "Saved" state is derived from that write and never from
 *     a sync result.
 *
 *  2. A sync re-applies every pending operation on top of the merged remote
 *     immediately before publishing. So an edit made *during* a sync is not
 *     merely preserved, it is actively folded into the next publish.
 *
 *  3. An operation leaves the outbox only after a revision containing it was
 *     accepted by the remote. A crash between publish and acknowledgement
 *     replays it, which is harmless because the merge is idempotent.
 */
class VaultSync(
    private val storage: VaultStorage,
    private val remote: RemoteVaultStore,
    private val clock: Clock,
    private val maxConflictRetries: Int = 5,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val syncMutex = Mutex()

    @Volatile
    private var statusValue = SyncStatus()

    fun status(): SyncStatus = statusValue

    /**
     * True only when every saved edit is in a published revision. The vault UI
     * must never claim "backed up" on anything weaker than this.
     */
    suspend fun isFullyBackedUp(): Boolean {
        val pending = storage.pending()
        statusValue = statusValue.copy(pending = pending.size)
        return pending.isEmpty() && statusValue.lastPublishedAtMs != null
    }

    suspend fun state(): VaultState = storage.readState()

    /**
     * Save one edit. Returns only once the edit is durable, so a caller may show
     * "Saved" the moment it returns — and must not show it before.
     */
    suspend fun commit(op: VaultOp): VaultState {
        val current = storage.readState()
        val next = applyOp(current, op)
        storage.commit(op, next)
        storage.writeClock(clock.snapshot())
        statusValue = statusValue.copy(pending = storage.pending().size)
        return next
    }

    // ---- Edit helpers: build a stamped operation and commit it. ----

    suspend fun putItem(
        itemId: String? = null,
        keyringId: String,
        fields: Map<ItemField, String>,
    ): VaultState = commit(
        VaultOp.ItemPut(
            opId = newId(),
            ts = clock.now(),
            itemId = itemId ?: newId(),
            keyringId = keyringId,
            fields = fields,
        ),
    )

    suspend fun deleteItem(itemId: String): VaultState =
        commit(VaultOp.ItemDelete(newId(), clock.now(), itemId))

    suspend fun restoreItem(itemId: String): VaultState =
        commit(VaultOp.ItemRestore(newId(), clock.now(), itemId))

    suspend fun moveItem(itemId: String, keyringId: String): VaultState =
        commit(VaultOp.ItemMove(newId(), clock.now(), itemId, keyringId))

    suspend fun putKeyring(keyringId: String? = null, name: String): VaultState =
        commit(VaultOp.KeyringPut(newId(), clock.now(), keyringId ?: newId(), name))

    suspend fun deleteKeyring(keyringId: String): VaultState =
        commit(VaultOp.KeyringDelete(newId(), clock.now(), keyringId))

    /** Serialised so two syncs never publish from the same base. */
    suspend fun sync(): SyncOutcome = syncMutex.withLock { syncNow() }

    private suspend fun syncNow(): SyncOutcome {
        statusValue = statusValue.copy(syncing = true)
        try {
            repeat(maxConflictRetries + 1) {
                val revision = try {
                    remote.read()
                } catch (error: RemoteUnavailableException) {
                    return offline(error)
                }

                // Adopt remote causal time so our next local write sorts after
                // it, even if this device's wall clock is behind.
                if (revision != null) {
                    clock.observe(maxHlc(revision.state))
                    storage.writeClock(clock.snapshot())
                }

                val local = storage.readState()
                val base = revision?.state ?: emptyVault()

                // Capture pending *before* the write. Anything committed after
                // this point stays queued rather than being falsely acked.
                val pending = storage.pending()
                val pendingIds = pending.map { it.opId }

                // Rule 2: fold every unacknowledged edit into what we are about
                // to publish. Idempotent, so re-applying published work is free.
                val merged = applyOps(mergeVaults(base, local), pending)

                if (revision != null && fingerprint(merged) == fingerprint(revision.state)) {
                    storage.applyRemote(merged)
                    storage.ack(pendingIds)
                    statusValue = statusValue.copy(
                        lastError = null,
                        lastPublishedAtMs = statusValue.lastPublishedAtMs ?: nowMs(),
                    )
                    return SyncOutcome.Unchanged(refreshPending())
                }

                val version = try {
                    remote.write(merged, revision?.version)
                } catch (conflict: VersionConflictException) {
                    return@repeat // re-read, re-merge
                } catch (error: RemoteUnavailableException) {
                    return offline(error)
                }

                storage.applyRemote(merged)

                // Rule 3: acknowledge only work we have *seen* in a published
                // revision, never work we merely uploaded. Google Drive offers
                // no compare-and-set, so a competing device can land a revision
                // between our freshness check and our upload. Acking on our own
                // write alone would let the loser of that race drop edits it had
                // already marked safe -- silent data loss, the exact failure
                // this engine exists to prevent. One extra read closes it.
                if (published(pending)) storage.ack(pendingIds)
                statusValue = statusValue.copy(lastPublishedAtMs = nowMs(), lastError = null)
                return SyncOutcome.Published(version, refreshPending())
            }

            statusValue = statusValue.copy(
                lastError = "Another device kept changing the vault while we were saving.",
            )
            return SyncOutcome.ConflictExhausted(refreshPending())
        } finally {
            statusValue = statusValue.copy(syncing = false)
        }
    }

    /**
     * Is every pending operation reflected in what the remote now holds?
     *
     * Uses the CRDT's idempotence: re-applying operations already present
     * cannot change the state, so an unchanged fingerprint proves they
     * survived. A failed read counts as unproven, keeping the work queued
     * rather than risking its loss.
     */
    private suspend fun published(pending: List<VaultOp>): Boolean {
        if (pending.isEmpty()) return true
        return try {
            val confirmed = remote.read() ?: return false
            fingerprint(applyOps(confirmed.state, pending)) == fingerprint(confirmed.state)
        } catch (error: Exception) {
            false
        }
    }

    private suspend fun offline(error: RemoteUnavailableException): SyncOutcome {
        val message = error.message ?: "The encrypted backup could not be reached."
        statusValue = statusValue.copy(lastError = message)
        return SyncOutcome.Offline(refreshPending(), message)
    }

    private suspend fun refreshPending(): Int {
        val count = storage.pending().size
        statusValue = statusValue.copy(pending = count)
        return count
    }
}
