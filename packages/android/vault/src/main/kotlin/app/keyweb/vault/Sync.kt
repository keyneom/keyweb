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
    /**
     * The remote for a keyring that lives in its own document.
     *
     * Returning null means that document has nowhere to publish yet — the
     * normal state for a keyring bound locally whose Drive file has not been
     * made. Its edits stay queued in its own outbox and go up when a remote
     * appears, exactly as an offline vault's do.
     */
    private val remoteFor: (String) -> RemoteVaultStore? = { documentId ->
        if (documentId == VAULT_DOCUMENT) remote else null
    },
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

    /**
     * The vault as a person sees it: its own items plus every dataset's.
     *
     * Composed on every read rather than cached, because a cache here is one
     * more thing that can disagree with storage, and the merge is cheap beside
     * the decryption that already happened to get the documents.
     */
    suspend fun state(): VaultState {
        val vault = storage.readState()
        val datasets = readDatasets(vault)
        return if (datasets.isEmpty()) vault else composeVault(vault, datasets)
    }

    /** Every bound document this device actually holds. */
    private suspend fun readDatasets(vault: VaultState): Map<String, VaultState> {
        val wanted = boundDatasets(vault).map { it.datasetId }.toSet()
        if (wanted.isEmpty()) return emptyMap()

        val held = storage.knownDocuments().toSet()
        // A keyring bound on another device arrives before its document does.
        // Skipping it shows the keyring empty rather than failing the read.
        return wanted.filter { it in held }.associateWith { storage.readState(it) }
    }

    /**
     * Save one edit. Returns only once the edit is durable, so a caller may show
     * "Saved" the moment it returns — and must not show it before.
     */
    suspend fun commit(op: VaultOp): VaultState = commitAll(listOf(op))

    /**
     * Save several edits as one durable write.
     *
     * Not a convenience wrapper around [commit]. Committing N edits separately
     * costs N reads and N writes of the *whole* vault — and since each write
     * re-encrypts everything and [VaultStorage.pending] re-reads the growing
     * outbox, the cost of deleting a keyring grew with the square of its size.
     * Deleting 200 passwords meant 201 whole-vault encryptions and sixty
     * thousand operation decryptions, which is why it appeared to hang.
     *
     * The ops are folded in memory and persisted once, in one transaction, so
     * the vault is never left holding half a bulk change.
     */
    suspend fun commitAll(ops: List<VaultOp>): VaultState {
        if (ops.isEmpty()) return state()

        // Every document is read once, here, and the result is composed from
        // what this produces rather than read back. Each read decrypts a whole
        // document, so re-reading to answer "what does it look like now" would
        // double the cost of every edit.
        val vault = storage.readState()
        val datasets = readDatasets(vault).toMutableMap()

        // Routed against the composed view: deciding where an edit goes needs
        // to know which keyring an item is on, and for an item in a shared
        // keyring that fact lives in the dataset rather than the vault.
        val composed = if (datasets.isEmpty()) vault else composeVault(vault, datasets)
        val byDocument = ops.groupBy { documentFor(it, composed) }

        // Sorted so the vault is written first. Its operations create and bind
        // keyrings, and a dataset written before the vault knew its keyring
        // existed would be a document nothing points at.
        var nextVault = vault
        for (documentId in byDocument.keys.sorted()) {
            val group = byDocument.getValue(documentId)
            val isVault = documentId == VAULT_DOCUMENT
            var after = if (isVault) vault else datasets[documentId] ?: emptyVault()
            for (op in group) after = applyOp(after, op)

            if (group.size == 1 && isVault) storage.commit(group.first(), after)
            else storage.commitAll(group, after, documentId)

            if (isVault) nextVault = after else datasets[documentId] = after
        }

        storage.writeClock(clock.snapshot())
        refreshPending()
        return if (datasets.isEmpty()) nextVault else composeVault(nextVault, datasets)
    }

    /**
     * A fresh operation id and causal timestamp from this engine's clock.
     *
     * For callers that build their own operations — the KeePass import is the
     * one — so they can hand a whole batch to [commitAll]. The stamp has to
     * come from here rather than from the caller: the clock is what orders an
     * import against edits made in between, and a clock of the caller's own
     * would not be the one the engine persists.
     */
    fun stamp(): Stamp = Stamp(newId(), clock.now())

    /** Stamp and delete several items in one write. */
    suspend fun deleteItems(itemIds: List<String>): VaultState =
        commitAll(itemIds.map { VaultOp.ItemDelete(newId(), clock.now(), it) })

    /** Stamp and move several items in one write. */
    suspend fun moveItems(itemIds: List<String>, keyringId: String): VaultState =
        commitAll(itemIds.map { VaultOp.ItemMove(newId(), clock.now(), it, keyringId) })

    /**
     * Delete a keyring and the passwords in it, as one write.
     *
     * The items come first in the op order, so a reader replaying the outbox
     * sees them deleted in their own right rather than merely orphaned by a
     * keyring that vanished.
     */
    suspend fun deleteKeyringWithItems(keyringId: String): VaultState {
        val current = storage.readState()
        val doomed = visibleItems(current).filter { it.keyring.value == keyringId }
        return commitAll(
            doomed.map { VaultOp.ItemDelete(newId(), clock.now(), it.id) } +
                VaultOp.KeyringDelete(newId(), clock.now(), keyringId),
        )
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

    /**
     * Sync the vault, then every keyring that lives in its own document.
     *
     * The vault goes first and its result is what the caller sees, because the
     * vault is what says which datasets exist — syncing them first would use
     * this device's idea of the bindings rather than the agreed one.
     *
     * A dataset that fails does not fail the whole sync. One shared keyring
     * whose file has been revoked must not stop the rest of someone's
     * passwords from backing up.
     */
    private suspend fun syncNow(): SyncOutcome {
        val outcome = syncDocument(VAULT_DOCUMENT, remote)

        for (binding in boundDatasets(storage.readState())) {
            val store = remoteFor(binding.datasetId) ?: continue
            runCatching { syncDocument(binding.datasetId, store) }
        }
        return when (outcome) {
            is SyncOutcome.Published -> outcome.copy(pending = refreshPending())
            is SyncOutcome.Unchanged -> outcome.copy(pending = refreshPending())
            else -> outcome
        }
    }

    private suspend fun syncDocument(documentId: String, store: RemoteVaultStore): SyncOutcome {
        statusValue = statusValue.copy(syncing = true)
        try {
            repeat(maxConflictRetries + 1) {
                val revision = try {
                    store.read()
                } catch (error: RemoteUnavailableException) {
                    return offline(error)
                }

                // Adopt remote causal time so our next local write sorts after
                // it, even if this device's wall clock is behind.
                if (revision != null) {
                    clock.observe(maxHlc(revision.state))
                    storage.writeClock(clock.snapshot())
                }

                val local = storage.readState(documentId)
                val base = revision?.state ?: emptyVault()

                // Capture pending *before* the write. Anything committed after
                // this point stays queued rather than being falsely acked.
                val pending = storage.pending(documentId)
                val pendingIds = pending.map { it.opId }

                // Rule 2: fold every unacknowledged edit into what we are about
                // to publish. Idempotent, so re-applying published work is free.
                val merged = applyOps(mergeVaults(base, local), pending)

                if (revision != null && fingerprint(merged) == fingerprint(revision.state)) {
                    storage.applyRemote(merged, documentId)
                    storage.ack(pendingIds, documentId)
                    statusValue = statusValue.copy(
                        lastError = null,
                        lastPublishedAtMs = statusValue.lastPublishedAtMs ?: nowMs(),
                    )
                    return SyncOutcome.Unchanged(refreshPending())
                }

                val version = try {
                    store.write(merged, revision?.version)
                } catch (conflict: VersionConflictException) {
                    return@repeat // re-read, re-merge
                } catch (error: RemoteUnavailableException) {
                    return offline(error)
                }

                storage.applyRemote(merged, documentId)

                // Rule 3: acknowledge only work we have *seen* in a published
                // revision, never work we merely uploaded. Google Drive offers
                // no compare-and-set, so a competing device can land a revision
                // between our freshness check and our upload. Acking on our own
                // write alone would let the loser of that race drop edits it had
                // already marked safe -- silent data loss, the exact failure
                // this engine exists to prevent. One extra read closes it.
                if (published(pending, store)) storage.ack(pendingIds, documentId)
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
    private suspend fun published(pending: List<VaultOp>, store: RemoteVaultStore): Boolean {
        if (pending.isEmpty()) return true
        return try {
            val confirmed = store.read() ?: return false
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

    /**
     * Work still queued, across every document.
     *
     * Counted over all of them because the status line speaks for the whole
     * vault: "3 changes still to back up" must not omit the ones waiting in a
     * shared keyring, or someone is told they are safe when they are not.
     */
    private suspend fun refreshPending(): Int {
        var count = storage.pending().size
        for (documentId in storage.knownDocuments()) count += storage.pending(documentId).size
        statusValue = statusValue.copy(pending = count)
        return count
    }

    /**
     * Which document an operation belongs in.
     *
     * Keyring operations always the vault: it is the vault that knows a
     * keyring exists, what it is called and where its items went, and a rename
     * landing only in a shared document would be invisible to the person who
     * shared it.
     *
     * Item operations follow their keyring. An edit to a password in a shared
     * keyring belongs in that keyring's document; putting it in the vault
     * would leave this device looking correct while the other person never saw
     * the change.
     */
    private fun documentFor(op: VaultOp, composed: VaultState): String = when (op) {
        is VaultOp.KeyringPut, is VaultOp.KeyringDelete, is VaultOp.KeyringBind -> VAULT_DOCUMENT
        is VaultOp.ItemPut -> datasetForItem(composed, op.keyringId) ?: VAULT_DOCUMENT
        is VaultOp.ItemMove -> datasetForItem(composed, op.keyringId) ?: VAULT_DOCUMENT
        is VaultOp.ItemDelete ->
            datasetForItem(composed, composed.items[op.itemId]?.keyring?.value) ?: VAULT_DOCUMENT
        is VaultOp.ItemRestore ->
            datasetForItem(composed, composed.items[op.itemId]?.keyring?.value) ?: VAULT_DOCUMENT
    }
}

/** An operation id and the causal time it happened at. */
data class Stamp(val opId: String, val ts: Hlc)
