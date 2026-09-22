package app.keyweb.vault

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Every user edit is an operation before it is a state change. Operations are
 * durable, replayable and idempotent: applying the same op twice produces the
 * same state, which is what makes crash-then-replay and sync retries safe.
 */
@Serializable
sealed interface VaultOp {
    val opId: String
    val ts: Hlc

    @Serializable
    @SerialName("item.put")
    data class ItemPut(
        override val opId: String,
        override val ts: Hlc,
        val itemId: String,
        val keyringId: String,
        val fields: Map<ItemField, String>,
    ) : VaultOp

    @Serializable
    @SerialName("item.delete")
    data class ItemDelete(
        override val opId: String,
        override val ts: Hlc,
        val itemId: String,
    ) : VaultOp

    /**
     * Tombstone an item *and* take its values with it.
     *
     * [ItemDelete] hides a password but leaves its value in the document,
     * which is what the undo window is built on. That is the wrong trade when
     * the document is one somebody else can read: dragging a password out of a
     * shared keyring has to actually take it away from the people it was
     * shared with, not merely stop showing it to them.
     *
     * So this blanks every field and drops the history as well. Still a set of
     * register writes rather than a removal, so it merges like everything
     * else — an edit made after the purge, on another device, still wins and
     * brings the value back, which is correct: that edit happened later.
     */
    @Serializable
    @SerialName("item.purge")
    data class ItemPurge(
        override val opId: String,
        override val ts: Hlc,
        val itemId: String,
    ) : VaultOp

    @Serializable
    @SerialName("item.restore")
    data class ItemRestore(
        override val opId: String,
        override val ts: Hlc,
        val itemId: String,
    ) : VaultOp

    @Serializable
    @SerialName("item.move")
    data class ItemMove(
        override val opId: String,
        override val ts: Hlc,
        val itemId: String,
        val keyringId: String,
    ) : VaultOp

    @Serializable
    @SerialName("keyring.put")
    data class KeyringPut(
        override val opId: String,
        override val ts: Hlc,
        val keyringId: String,
        val name: String,
    ) : VaultOp

    /**
     * Move a keyring's items into their own document, or back into the vault.
     *
     * An empty [datasetId] means the vault. The operation records *where* the
     * items belong; moving them is the caller's job, because it spans two
     * documents and only storage can do that atomically.
     */
    @Serializable
    @SerialName("keyring.bind")
    data class KeyringBind(
        override val opId: String,
        override val ts: Hlc,
        val keyringId: String,
        val datasetId: String,
        /** Set when the shared document's keyring id is not [keyringId]. */
        val sourceKeyringId: String? = null,
    ) : VaultOp

    @Serializable
    @SerialName("keyring.delete")
    data class KeyringDelete(
        override val opId: String,
        override val ts: Hlc,
        val keyringId: String,
    ) : VaultOp
}

private fun pushHistory(history: List<HistoryEntry>, entry: HistoryEntry): List<HistoryEntry> =
    capHistory(listOf(entry) + history.filterNot { it.field == entry.field && it.ts == entry.ts })

/**
 * Apply one operation. Pure: returns a new state and never mutates the input.
 *
 * Idempotent by construction — every write goes through a register whose HLC
 * decides the winner, so replaying an op already reflected is a no-op.
 */
fun applyOp(state: VaultState, op: VaultOp): VaultState = when (op) {
    is VaultOp.KeyringPut -> {
        val existing = state.keyrings[op.keyringId] ?: newKeyring(op.keyringId, op.name, op.ts)
        val next = existing.copy(
            name = pickReg(existing.name, Reg(op.name, op.ts)) ?: Reg(op.name, op.ts),
        )
        state.copy(keyrings = state.keyrings + (op.keyringId to next))
    }

    is VaultOp.KeyringBind -> {
        val existing = state.keyrings[op.keyringId] ?: newKeyring(op.keyringId, "", op.ts)
        val incoming = Reg(op.datasetId, op.ts)
        val sourceId = op.sourceKeyringId
        val source = if (!sourceId.isNullOrEmpty() && sourceId != op.keyringId) {
            val nextSource = Reg(sourceId, op.ts)
            pickReg(existing.source, nextSource) ?: nextSource
        } else {
            existing.source
        }
        val next = existing.copy(
            dataset = pickReg(existing.dataset, incoming) ?: incoming,
            source = source,
        )
        state.copy(keyrings = state.keyrings + (op.keyringId to next))
    }

    is VaultOp.KeyringDelete -> {
        val existing = state.keyrings[op.keyringId] ?: newKeyring(op.keyringId, "", op.ts)
        val next = existing.copy(
            deleted = pickReg(existing.deleted, Reg(true, op.ts)) ?: Reg(true, op.ts),
        )
        state.copy(keyrings = state.keyrings + (op.keyringId to next))
    }

    is VaultOp.ItemPut -> {
        val existing = state.items[op.itemId] ?: newItem(op.itemId, op.keyringId, op.ts)
        val fields = existing.fields.toMutableMap()
        var history = existing.history
        for ((field, value) in op.fields) {
            val incoming = Reg(value, op.ts)
            val current = fields[field]
            val winner = pickReg(current, incoming) ?: incoming
            // Retain a superseded value so an accidental overwrite is recoverable.
            if (current != null && winner.ts != current.ts && current.value != winner.value) {
                history = pushHistory(history, HistoryEntry(field, current.value, current.ts))
            }
            fields[field] = winner
        }
        val next = existing.copy(
            keyring = pickReg(existing.keyring, Reg(op.keyringId, op.ts))
                ?: Reg(op.keyringId, op.ts),
            // Saving an item revives it if it was deleted earlier. Losing a save
            // is the worse failure, so an edit after a delete wins and the item
            // comes back; an edit from before the delete still loses.
            deleted = pickReg(existing.deleted, Reg(false, op.ts)) ?: Reg(false, op.ts),
            fields = fields,
            history = history,
        )
        state.copy(items = state.items + (op.itemId to next))
    }

    is VaultOp.ItemDelete, is VaultOp.ItemRestore -> {
        val itemId = when (op) {
            is VaultOp.ItemDelete -> op.itemId
            is VaultOp.ItemRestore -> op.itemId
            else -> error("unreachable")
        }
        val wanted = op is VaultOp.ItemDelete
        val existing = state.items[itemId] ?: newItem(itemId, "", op.ts)
        val next = existing.copy(
            deleted = pickReg(existing.deleted, Reg(wanted, op.ts)) ?: Reg(wanted, op.ts),
        )
        state.copy(items = state.items + (itemId to next))
    }

    is VaultOp.ItemPurge -> {
        val existing = state.items[op.itemId] ?: newItem(op.itemId, "", op.ts)
        // Only the fields this document actually holds can be blanked. A field
        // written by a newer client and not yet merged here is not in the
        // record to blank, and is carried through untouched — the same
        // limitation every field-level write has.
        val fields = existing.fields.mapValues { (_, value) ->
            pickReg(value, Reg("", op.ts)) ?: Reg("", op.ts)
        }
        val next = existing.copy(
            deleted = pickReg(existing.deleted, Reg(true, op.ts)) ?: Reg(true, op.ts),
            fields = fields,
            history = emptyList(),
        )
        state.copy(items = state.items + (op.itemId to next))
    }

    is VaultOp.ItemMove -> {
        val existing = state.items[op.itemId] ?: newItem(op.itemId, op.keyringId, op.ts)
        val next = existing.copy(
            keyring = pickReg(existing.keyring, Reg(op.keyringId, op.ts))
                ?: Reg(op.keyringId, op.ts),
        )
        state.copy(items = state.items + (op.itemId to next))
    }
}

fun applyOps(state: VaultState, ops: List<VaultOp>): VaultState = ops.fold(state, ::applyOp)
