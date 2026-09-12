package app.keyweb.vault

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The vault state is a CRDT: a map of registers, each carrying the HLC of the
 * write that produced it. Merging two states is a join — commutative,
 * associative and idempotent — which is what lets a sync retry, a duplicate
 * delivery, or a crashed-then-replayed operation all be harmless.
 *
 * Resolution is per *field*, not per item, so two people editing the same login
 * at once keep both edits when they touched different fields.
 *
 * The serialised shape is byte-identical to the TypeScript implementation in
 * packages/vault-core. Both platforms read the same Drive payload, so any change
 * here is a wire-format change and must be made in both.
 */

/** A single last-write-wins register. */
@Serializable
data class Reg<T>(val value: T, val ts: Hlc)

/** Take the later of two registers. Ties break on the encoded HLC, which
 *  embeds the node id, so every replica picks the same winner. */
fun <T> pickReg(a: Reg<T>?, b: Reg<T>?): Reg<T>? {
    if (a == null) return b
    if (b == null) return a
    if (a.ts == b.ts) return a
    return if (a.ts > b.ts) a else b
}

/**
 * `FOLDER` and `TAGS` exist to preserve structure brought in from elsewhere.
 * KeePass and KeeWeb organise entries into nested groups and tag them, and
 * dropping that on import would turn an organised vault into an
 * undifferentiated list. They are plain fields, so they inherit the same
 * per-field merge as everything else.
 */
@Serializable
enum class ItemField {
    @SerialName("title") TITLE,
    @SerialName("username") USERNAME,
    @SerialName("password") PASSWORD,
    @SerialName("url") URL,
    @SerialName("note") NOTE,
    @SerialName("folder") FOLDER,
    @SerialName("tags") TAGS;

    /** The wire name, which is what the TypeScript side uses as its map key. */
    val wire: String
        get() = name.lowercase()

    companion object {
        fun fromWire(value: String): ItemField? =
            entries.firstOrNull { it.wire == value }
    }
}

@Serializable
data class HistoryEntry(
    val field: ItemField,
    val value: String,
    /** HLC of the write being superseded. */
    val ts: Hlc,
)

/** How many superseded values to retain per item for the undo window. */
const val HISTORY_LIMIT = 12

@Serializable
data class ItemRecord(
    val id: String,
    /** Which keyring the item lives on. Moving is itself a LWW register. */
    val keyring: Reg<String>,
    val fields: Map<ItemField, Reg<String>> = emptyMap(),
    /** Tombstone. Deletion is a register, not a removal, so a delete can lose
     *  to a later edit and an edit can lose to a later delete, deterministically. */
    val deleted: Reg<Boolean>,
    /** Superseded field values, newest first, capped at [HISTORY_LIMIT]. */
    val history: List<HistoryEntry> = emptyList(),
)

@Serializable
data class KeyringRecord(
    val id: String,
    val name: Reg<String>,
    val deleted: Reg<Boolean>,
)

@Serializable
data class VaultState(
    val items: Map<String, ItemRecord> = emptyMap(),
    val keyrings: Map<String, KeyringRecord> = emptyMap(),
)

fun emptyVault(): VaultState = VaultState()

fun newItem(id: String, keyringId: String, ts: Hlc): ItemRecord =
    ItemRecord(
        id = id,
        keyring = Reg(keyringId, ts),
        fields = emptyMap(),
        deleted = Reg(false, HLC_ZERO),
        history = emptyList(),
    )

fun newKeyring(id: String, name: String, ts: Hlc): KeyringRecord =
    KeyringRecord(id = id, name = Reg(name, ts), deleted = Reg(false, HLC_ZERO))

/** Items a person can actually see: not deleted, on a keyring that exists. */
fun visibleItems(state: VaultState): List<ItemRecord> =
    state.items.values
        .filter { !it.deleted.value }
        .filter { item ->
            val ring = state.keyrings[item.keyring.value]
            ring != null && !ring.deleted.value
        }

fun ItemRecord.field(field: ItemField): String? = fields[field]?.value

/**
 * A stable content fingerprint. Two states that would present identically
 * produce the same string, so a pointless upload can be skipped — but it
 * deliberately covers timestamps too, because a state whose registers advanced
 * is genuinely different and must be published for other devices to converge.
 */
fun fingerprint(state: VaultState): String {
    val items = state.items.keys.sorted().joinToString(";") { id ->
        val item = state.items[id] ?: return@joinToString ""
        val fields = item.fields.keys
            .sortedBy { it.wire }
            .joinToString(",") { name ->
                val reg = item.fields[name]
                if (reg == null) "" else "${name.wire}=${reg.ts}:${reg.value}"
            }
        "$id|${item.keyring.ts}:${item.keyring.value}|${item.deleted.ts}:${item.deleted.value}|$fields"
    }
    val keyrings = state.keyrings.keys.sorted().joinToString(";") { id ->
        val ring = state.keyrings[id] ?: return@joinToString ""
        "$id|${ring.name.ts}:${ring.name.value}|${ring.deleted.ts}:${ring.deleted.value}"
    }
    return "v1:$items#$keyrings"
}
