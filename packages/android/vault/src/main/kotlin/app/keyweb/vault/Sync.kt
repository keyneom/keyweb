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
    /**
     * The backup exists and this vault's key does not open it.
     *
     * A flag rather than something inferred from [lastError], because the one
     * action that helps here — overwriting the backup with this device's copy
     * — is destructive, and offering it because a string matched would be a
     * bad way to decide that.
     */
    val backupUnreadable: Boolean = false,
    /**
     * Unreadable because it is *newer*, not because it is foreign.
     *
     * Kept apart from [backupUnreadable] because the two look identical from
     * here and want opposite actions: one asks for this device to catch up,
     * the other offers to overwrite. Offering the overwrite for this one
     * would destroy the very passwords that could not be read.
     */
    val backupBehind: Boolean = false,
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

    /**
     * One document's own state, without the rest of the vault composed in.
     *
     * For a caller that has to publish a document as its own thing — sharing a
     * keyring is the one — where composing would hand over every other keyring
     * as well.
     */
    suspend fun documentState(documentId: String): VaultState = storage.readState(documentId)

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

        // The vault is written **last**, and that order is the only thing
        // standing between a crash and a lost password.
        //
        // An edit that moves a password between documents is a tombstone in
        // the one it left and a copy in the one it arrived at. Writing the
        // tombstone first and then failing would destroy the only copy.
        // Writing the copy first and then failing leaves the password in both
        // places, which the merge resolves the moment either is read again.
        //
        // The cost is that a dataset can briefly be a document the vault does
        // not yet point at. That is recoverable by repeating the operation;
        // the other way round is not recoverable by anything.
        var nextVault = vault
        for (documentId in byDocument.keys.sortedDescending()) {
            val group = byDocument.getValue(documentId)
            val isVault = documentId == VAULT_DOCUMENT
            // A keyring adopted under a fresh id is stored in the shared
            // document under the id that document already uses.
            val writing = if (isVault) group else group.map { forDataset(it, vault) }
            var after = if (isVault) vault else datasets[documentId] ?: emptyVault()
            for (op in writing) after = applyOp(after, op)

            if (writing.size == 1 && isVault) storage.commit(writing.first(), after)
            else storage.commitAll(writing, after, documentId)

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

    /**
     * Stamp and delete several items in one write, with their files.
     *
     * A file left behind when its password is deleted is worse than a visible
     * one: it is somebody's scanned passport, still in the vault and still in
     * the backup, with nothing on any screen to remind them it is there.
     */
    suspend fun deleteItems(itemIds: List<String>): VaultState =
        commitAll(deletionOps(state(), itemIds))

    /**
     * Deleting these items, and emptying whatever files only they were holding.
     *
     * Shared by deleting one password, several, and a whole keyring, because
     * the rule has to be the same in all three.
     *
     * A file is purged rather than tombstoned, for the reason
     * [VaultOp.ItemPurge] exists — in a password manager, deleted has to mean
     * the bytes go. The passwords themselves are only tombstoned, because
     * their superseded values are the undo window and a password is small.
     */
    private fun deletionOps(composed: VaultState, itemIds: List<String>): List<VaultOp> {
        val doomed = itemIds.toSet()

        // A file referenced by a password that is *not* being deleted
        // survives. Blobs are content-addressed, so the same file attached
        // twice is stored once, and deleting one of the two must not empty
        // the other.
        val orphaned = linkedSetOf<String>()
        for (itemId in itemIds) {
            val item = composed.items[itemId] ?: continue
            for (file in item.attachments()) orphaned += file.blobId
            if (item.isBlob()) orphaned += item.id
        }
        for (item in liveItems(composed)) {
            if (doomed.contains(item.id)) continue
            for (file in item.attachments()) orphaned -= file.blobId
        }

        return itemIds.filterNot { orphaned.contains(it) }
            .map { VaultOp.ItemDelete(newId(), clock.now(), it) } +
            orphaned.map { VaultOp.ItemPurge(newId(), clock.now(), it) }
    }

    /**
     * Attach a file to a password.
     *
     * The bytes become an item of their own on the same keyring, so they are
     * encrypted, merged, shared and deleted by the machinery that already
     * exists — and the password gains one field pointing at them.
     *
     * [blobId] is content-derived and supplied by the caller, because hashing
     * is the one platform-specific part and this module deliberately touches
     * no crypto. Content-addressing is what makes the same file attached to
     * two passwords cost one copy, and what makes two devices attaching the
     * same file converge instead of duplicating it.
     */
    suspend fun attachFile(
        itemId: String,
        keyringId: String,
        blobId: String,
        name: String,
        type: String,
        data: String,
        /**
         * The file's real size, not the length of its base64.
         *
         * Base64 is a third larger and rounds, so deriving the size from it
         * told somebody their 395-byte file was 396 bytes.
         */
        bytes: Int,
    ): VaultState = commitAll(
        listOf(
            VaultOp.ItemPut(
                opId = newId(),
                ts = clock.now(),
                itemId = blobId,
                keyringId = keyringId,
                fields = mapOf(
                    "kind" to BLOB_KIND,
                    "name" to name,
                    "type" to type,
                    "size" to bytes.toString(),
                    // Prefixed so it is masked, never rendered, never logged.
                    "secret:data" to data,
                ),
            ),
            VaultOp.ItemPut(
                opId = newId(),
                ts = clock.now(),
                itemId = itemId,
                keyringId = keyringId,
                fields = mapOf(attachmentField(blobId) to name),
            ),
        ),
    )

    /**
     * Take a file off a password, and out of the vault if nothing else wants it.
     *
     * Purged rather than tombstoned, so the bytes actually go. Kept when
     * another password still references the same content, which
     * content-addressing makes possible to know.
     */
    suspend fun removeAttachment(itemId: String, blobId: String): VaultState {
        val composed = state()
        val referenced = liveItems(composed).any { item ->
            item.id != itemId && item.attachments().any { it.blobId == blobId }
        }
        return commitAll(
            buildList {
                add(
                    VaultOp.ItemPut(
                        opId = newId(),
                        ts = clock.now(),
                        itemId = itemId,
                        keyringId = composed.items[itemId]?.keyring?.value.orEmpty(),
                        // Emptied rather than removed: a CRDT has no way to say
                        // "this field is gone" except by writing a later value,
                        // and empty is what the readers already treat as none.
                        fields = mapOf(attachmentField(blobId) to ""),
                    ),
                )
                if (!referenced) add(VaultOp.ItemPurge(newId(), clock.now(), blobId))
            },
        )
    }

    /**
     * Stamp and move several items in one write.
     *
     * A move within one document is just a change of keyring. A move that
     * leaves one document for another cannot be, for the reason [relocation]
     * explains: the item would stay behind in the document it left. That
     * matters most in the direction people care about — dragging a password
     * *out* of a shared keyring has to actually take it away from the people
     * it was shared with, not merely stop showing it here.
     */
    suspend fun moveItems(itemIds: List<String>, keyringId: String): VaultState {
        val vault = storage.readState()
        val datasets = readDatasets(vault)
        val composed = if (datasets.isEmpty()) vault else composeVault(vault, datasets)
        val destination = datasetForItem(composed, keyringId) ?: VAULT_DOCUMENT

        val ops = buildList {
            for (itemId in itemIds) {
                val item = composed.items[itemId] ?: continue
                val source = datasetForItem(composed, item.keyring.value) ?: VAULT_DOCUMENT
                if (source == destination) {
                    add(VaultOp.ItemMove(newId(), clock.now(), itemId, keyringId))
                } else {
                    addAll(relocation(item, keyringId))
                }
            }
        }
        return commitAll(ops)
    }

    /**
     * Delete a keyring and the passwords in it, as one write.
     *
     * The items come first in the op order, so a reader replaying the outbox
     * sees them deleted in their own right rather than merely orphaned by a
     * keyring that vanished.
     */
    suspend fun deleteKeyringWithItems(keyringId: String): VaultState {
        // Composed, and files included: a keyring's attachments are items on
        // it, and leaving them would keep somebody's scanned passport in the
        // vault after they deleted the folder they put it in.
        val current = state()
        val doomed = liveItems(current).filter { it.keyring.value == keyringId }
        return commitAll(
            deletionOps(current, doomed.map { it.id }) +
                VaultOp.KeyringDelete(newId(), clock.now(), keyringId),
        )
    }

    /**
     * The two operations that carry one password from one document to another.
     *
     * A CRDT cannot forget. Merging never removes anything, so an item simply
     * left out of a document's next state comes straight back the moment that
     * document is joined with a revision that still has it — which is what
     * made the first attempt at this silently republish every password it had
     * just moved out. The only way to say "not here any more" is to say it in
     * the language the merge understands: a tombstone.
     *
     * So a relocation is a purge in the document it leaves and a full copy in
     * the document it arrives in, and the copy is stamped **after** the purge.
     * That ordering is what makes the pair survive being merged in either
     * order on any device: wherever the two meet, the live copy is the later
     * write and wins.
     *
     * The undo history does not travel. It is a local window onto values this
     * document once held, and carrying it into a keyring somebody else can
     * read would hand them superseded passwords they were never shown.
     */
    private fun relocation(item: ItemRecord, keyringId: String): List<VaultOp> = listOf(
        VaultOp.ItemPurge(newId(), clock.now(), item.id),
        VaultOp.ItemPut(
            opId = newId(),
            ts = clock.now(),
            itemId = item.id,
            keyringId = keyringId,
            fields = item.fields.mapValues { (_, value) -> value.value },
        ),
    )

    /**
     * Everything live on a keyring, files included.
     *
     * [visibleItems] would be wrong here: it hides the files attached to
     * passwords, and moving a keyring without them would leave them behind in
     * the document it came from — orphaned, and still readable by whoever
     * holds that document.
     */
    private fun itemsOn(composed: VaultState, keyringId: String): List<ItemRecord> =
        liveItems(composed).filter { it.keyring.value == keyringId }

    /**
     * Move a keyring's passwords into their own document, so it can be shared.
     *
     * Two writes, and the dataset is written first. In between, the passwords
     * exist in both documents — harmless, because the purge that removes them
     * from the vault is the *second* write, and until it lands the vault still
     * holds the originals. The other order would delete them from the vault
     * before anything else held them.
     *
     * Nothing here encrypts, uploads, or talks to anyone. Binding is the local
     * rearrangement only; a bound keyring with no remote yet simply queues,
     * and sharing it with a person is a separate step.
     */
    suspend fun bindKeyring(
        keyringId: String,
        datasetId: String,
        sourceKeyringId: String? = null,
    ): VaultState {
        require(datasetId.isNotEmpty()) { "A shared keyring needs a document to live in." }
        val vault = storage.readState()
        val datasets = readDatasets(vault).toMutableMap()
        val composed = if (datasets.isEmpty()) vault else composeVault(vault, datasets)

        val keyring = composed.keyrings[keyringId] ?: error("That keyring doesn't exist.")
        val already = datasetOf(keyring)
        if (already == datasetId) return composed
        check(already == null) { "That keyring already lives in its own document." }

        val alias = sourceKeyringId?.takeIf { it.isNotEmpty() && it != keyringId }
        val relocations = itemsOn(composed, keyringId).map { relocation(it, alias ?: keyringId) }

        // Read rather than taken from the composed map, which only holds
        // documents that are *already* bound. This one is not, and a document
        // just pulled down — a keyring somebody else shared — would otherwise
        // be overwritten with an empty one.
        val base = storage.readState(datasetId)

        // The name is written into the dataset only when the dataset does not
        // already agree with it. An alias skips this: the shared document
        // already has its keyring, and a reader must not rename it.
        val datasetOps = buildList {
            val named = base.keyrings[alias ?: keyringId]
            if (alias == null && named?.name?.value != keyring.name.value) {
                add(VaultOp.KeyringPut(newId(), clock.now(), keyringId, keyring.name.value))
            }
            for (pair in relocations) add(pair[1])
        }
        val dataset = applyOps(base, datasetOps)
        if (datasetOps.isNotEmpty()) storage.commitAll(datasetOps, dataset, datasetId)

        val vaultOps = buildList {
            for (pair in relocations) add(pair[0])
            add(VaultOp.KeyringBind(newId(), clock.now(), keyringId, datasetId, alias))
        }
        val remaining = applyOps(vault, vaultOps)
        storage.commitAll(vaultOps, remaining, VAULT_DOCUMENT)

        datasets[datasetId] = dataset
        storage.writeClock(clock.snapshot())
        refreshPending()
        return composeVault(remaining, datasets)
    }

    /**
     * Take a document's remote state in, for one that has just become readable.
     *
     * A keyring somebody shared exists in Drive before it exists here. This is
     * how its contents arrive before there is any binding pointing at them — a
     * join, in other words, not an assignment, so a document already holding
     * something keeps it.
     */
    suspend fun adoptDocument(documentId: String, state: VaultState): VaultState =
        storage.applyRemote(state, documentId)

    /**
     * Bring a keyring's passwords home and stop treating it as shared.
     *
     * For the owner un-sharing something of their own. Deliberately *not*
     * symmetric with binding: the vault takes copies, and the shared document
     * is left exactly as it is rather than purged. Purging it would empty the
     * file out from under anyone still holding a grant, and un-sharing is
     * meant to stop new reading, not to reach into what somebody already has.
     *
     * Because the document keeps its contents, re-sharing this keyring must
     * mint a fresh dataset id. Re-binding to the old one would resurrect
     * whatever it still holds — including passwords deleted in the meantime.
     */
    suspend fun unbindKeyring(keyringId: String): VaultState {
        val vault = storage.readState()
        val datasets = readDatasets(vault).toMutableMap()
        val composed = if (datasets.isEmpty()) vault else composeVault(vault, datasets)

        val datasetId = datasetOf(composed.keyrings[keyringId]) ?: return composed

        val ops = buildList {
            for (item in itemsOn(composed, keyringId)) add(relocation(item, keyringId)[1])
            add(
                VaultOp.KeyringPut(
                    newId(),
                    clock.now(),
                    keyringId,
                    composed.keyrings[keyringId]?.name?.value.orEmpty(),
                ),
            )
            add(VaultOp.KeyringBind(newId(), clock.now(), keyringId, ""))
        }

        val returned = applyOps(vault, ops)
        storage.commitAll(ops, returned, VAULT_DOCUMENT)

        datasets.remove(datasetId)
        storage.writeClock(clock.snapshot())
        refreshPending()
        return if (datasets.isEmpty()) returned else composeVault(returned, datasets)
    }

    /**
     * Stop carrying a keyring somebody else shared.
     *
     * Deliberately not [deleteKeyringWithItems]. The passwords belong to the
     * person who shared them, and deleting them here would delete them for
     * everybody — the tombstones would publish straight back into the shared
     * document. So the keyring is tombstoned in *this* vault only, which the
     * routing rules guarantee stays local, and the binding is cleared so the
     * document stops syncing.
     *
     * What it cannot do is take back what was already read. Leaving is the app
     * forgetting a keyring, not the passwords becoming unseen.
     */
    suspend fun leaveKeyring(keyringId: String): VaultState {
        val vault = storage.readState()
        val datasetId = datasetOf(vault.keyrings[keyringId])
        val ops = listOf(
            VaultOp.KeyringBind(newId(), clock.now(), keyringId, ""),
            VaultOp.KeyringDelete(newId(), clock.now(), keyringId),
        )
        storage.commitAll(ops, applyOps(vault, ops), VAULT_DOCUMENT)

        // The local copy goes too. Leaving it would mean "remove it from my
        // vault" left every password in it sitting on the device — listed
        // nowhere, which is worse than visible, because nothing would ever
        // prompt anyone to remove it. Second, and after the vault write: a
        // crash in between leaves a document nothing points at, which the next
        // leave clears, rather than a binding pointing at a document that has
        // gone.
        if (datasetId != null) storage.forgetDocument(datasetId)

        storage.writeClock(clock.snapshot())
        refreshPending()
        return state()
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

    /** One password, and any file that was only attached to it. */
    suspend fun deleteItem(itemId: String): VaultState = deleteItems(listOf(itemId))

    suspend fun restoreItem(itemId: String): VaultState =
        commit(VaultOp.ItemRestore(newId(), clock.now(), itemId))

    /** One password to another keyring, crossing documents if it has to. */
    suspend fun moveItem(itemId: String, keyringId: String): VaultState =
        moveItems(listOf(itemId), keyringId)

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
                } catch (error: BackupUnreadableException) {
                    /*
                     * Stop. Do not publish.
                     *
                     * The one thing that must never happen here is treating a
                     * backup we cannot read as a backup that is not there:
                     * that turns "I can't open this" into "I'll overwrite it",
                     * which is exactly how a browser wiped a phone's backup.
                     * The local vault is untouched and the pending queue keeps
                     * its work; somebody is told, and decides.
                     */
                    statusValue = statusValue.copy(
                        lastError = error.message
                            ?: "This backup was not written by this vault.",
                        backupUnreadable = true,
                        backupBehind = error is BackupBehindException,
                    )
                    return SyncOutcome.Offline(
                        refreshPending(),
                        error.message ?: "This backup was not written by this vault.",
                    )
                }

                // Adopt remote causal time so our next local write sorts after
                // it, even if this device's wall clock is behind. Only the
                // vault document is ours to trust: a collaborator who
                // publishes a year-3000 timestamp in a shared file would
                // otherwise pin this clock — and every other device we own,
                // once they sync the vault — so that every later conflict in
                // the private vault is lost forever.
                if (revision != null && documentId == VAULT_DOCUMENT) {
                    clock.observe(maxHlc(revision.state))
                    storage.writeClock(clock.snapshot())
                }

                statusValue = statusValue.copy(backupUnreadable = false, backupBehind = false)
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
                    store.write(merged, revision?.version, createOnly = revision == null)
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
     * Item operations follow their keyring. An edit to a password in a shared
     * keyring belongs in that keyring's document; putting it in the vault
     * would leave this device looking correct while the other person never saw
     * the change.
     *
     * A rename follows the keyring too, once it is shared. The name is part of
     * what was shared — two people looking at the same keyring should not be
     * looking at differently-named things — so it belongs beside the items
     * rather than in a vault only one of them can read.
     *
     * Binding and deleting stay in the vault whatever happens. [VaultOp.KeyringBind]
     * is this device's record of where the items went, and writing it into the
     * document it describes would be circular. [VaultOp.KeyringDelete] is the
     * subtler one: for a keyring somebody else shared, "delete" means *leave*,
     * and a tombstone published into the shared document would delete it out
     * from under everyone else instead.
     */
    /** Rewrite a local keyring id to the one the shared document uses. */
    private fun forDataset(op: VaultOp, vault: VaultState): VaultOp {
        fun sourceOf(id: String): String = sourceKeyringId(vault.keyrings[id], id)
        return when (op) {
            is VaultOp.ItemPut -> {
                val source = sourceOf(op.keyringId)
                if (source == op.keyringId) op else op.copy(keyringId = source)
            }
            is VaultOp.ItemMove -> {
                val source = sourceOf(op.keyringId)
                if (source == op.keyringId) op else op.copy(keyringId = source)
            }
            is VaultOp.KeyringPut -> {
                val source = sourceOf(op.keyringId)
                if (source == op.keyringId) op else op.copy(keyringId = source)
            }
            else -> op
        }
    }

    private fun documentFor(op: VaultOp, composed: VaultState): String = when (op) {
        is VaultOp.KeyringDelete, is VaultOp.KeyringBind -> VAULT_DOCUMENT
        is VaultOp.KeyringPut -> datasetOf(composed.keyrings[op.keyringId]) ?: VAULT_DOCUMENT
        is VaultOp.ItemPut -> datasetForItem(composed, op.keyringId) ?: VAULT_DOCUMENT
        is VaultOp.ItemMove -> datasetForItem(composed, op.keyringId) ?: VAULT_DOCUMENT
        is VaultOp.ItemDelete ->
            datasetForItem(composed, composed.items[op.itemId]?.keyring?.value) ?: VAULT_DOCUMENT
        is VaultOp.ItemPurge ->
            datasetForItem(composed, composed.items[op.itemId]?.keyring?.value) ?: VAULT_DOCUMENT
        is VaultOp.ItemRestore ->
            datasetForItem(composed, composed.items[op.itemId]?.keyring?.value) ?: VAULT_DOCUMENT
    }
}

/** An operation id and the causal time it happened at. */
data class Stamp(val opId: String, val ts: Hlc)
