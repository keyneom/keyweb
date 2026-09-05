package app.keyweb.vault

private fun mergeHistory(a: List<HistoryEntry>, b: List<HistoryEntry>): List<HistoryEntry> =
    (a + b)
        .distinctBy { it.field to it.ts }
        .sortedByDescending { it.ts }
        .take(HISTORY_LIMIT)

private fun mergeItem(a: ItemRecord, b: ItemRecord): ItemRecord {
    val fields = buildMap {
        for (name in a.fields.keys + b.fields.keys) {
            pickReg(a.fields[name], b.fields[name])?.let { put(name, it) }
        }
    }
    return ItemRecord(
        id = a.id,
        keyring = pickReg(a.keyring, b.keyring) ?: a.keyring,
        deleted = pickReg(a.deleted, b.deleted) ?: a.deleted,
        fields = fields,
        history = mergeHistory(a.history, b.history),
    )
}

private fun mergeKeyring(a: KeyringRecord, b: KeyringRecord): KeyringRecord =
    KeyringRecord(
        id = a.id,
        name = pickReg(a.name, b.name) ?: a.name,
        deleted = pickReg(a.deleted, b.deleted) ?: a.deleted,
    )

/**
 * Join two vault states.
 *
 * This is a CRDT merge, so it is commutative (merge(a,b) == merge(b,a)),
 * associative, and idempotent (merge(a,a) == a). Those three properties are the
 * reason the sync engine can retry a failed publish, re-apply an operation it
 * already published, or process the same remote revision twice without ever
 * losing or duplicating a change.
 */
fun mergeVaults(a: VaultState, b: VaultState): VaultState {
    val items = buildMap {
        for (id in a.items.keys + b.items.keys) {
            val left = a.items[id]
            val right = b.items[id]
            val merged = when {
                left != null && right != null -> mergeItem(left, right)
                left != null -> left
                right != null -> right
                else -> null
            }
            merged?.let { put(id, it) }
        }
    }
    val keyrings = buildMap {
        for (id in a.keyrings.keys + b.keyrings.keys) {
            val left = a.keyrings[id]
            val right = b.keyrings[id]
            val merged = when {
                left != null && right != null -> mergeKeyring(left, right)
                left != null -> left
                right != null -> right
                else -> null
            }
            merged?.let { put(id, it) }
        }
    }
    return VaultState(items = items, keyrings = keyrings)
}

/** The highest causal timestamp anywhere in a state. */
fun maxHlc(state: VaultState): Hlc {
    var max = HLC_ZERO
    fun bump(ts: Hlc) { if (ts > max) max = ts }
    for (item in state.items.values) {
        bump(item.keyring.ts)
        bump(item.deleted.ts)
        for (field in item.fields.values) bump(field.ts)
    }
    for (ring in state.keyrings.values) {
        bump(ring.name.ts)
        bump(ring.deleted.ts)
    }
    return max
}
