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
/**
 * A field key, as it appears on the wire.
 *
 * A plain string, deliberately. This was an enum, and that was a
 * forward-compatibility hazard rather than a safety feature: kotlinx
 * serialization enforces enum membership at parse time, so one field key from a
 * newer client made the **entire vault** fail to deserialize on an older
 * device. Not "the unknown field is hidden" — every password in it, unreadable.
 *
 * Open keys also make the feature possible: a crypto wallet's seed phrase, a
 * card's expiry, a field someone typed a name for themselves. The CRDT never
 * cared what the key was — each one is an independent register — so nothing in
 * the merge needed to change to allow this.
 */
typealias ItemField = String

/** The keys Keyweb gives special presentation to. Not the only keys allowed. */
object Fields {
    const val TITLE = "title"
    const val USERNAME = "username"
    const val PASSWORD = "password"
    const val URL = "url"
    const val NOTE = "note"

    /** The TOTP shared secret, base32 as the site issues it. */
    const val OTP = "otp"
    const val FOLDER = "folder"
    const val TAGS = "tags"

    /**
     * The keys Keyweb gives special presentation to.
     *
     * The mirror of the web's `ITEM_FIELDS`. An imported field whose name
     * collides with one of these is prefixed rather than allowed to overwrite
     * it — a KeePass field called "folder" must not be able to move an entry.
     */
    val KNOWN: Set<String> = setOf(
        TITLE, USERNAME, PASSWORD, URL, NOTE, OTP, FOLDER, TAGS, "kind",
    )

    /** True for a field whose value must never be shown without asking. */
    fun isSecret(field: ItemField): Boolean =
        field == PASSWORD ||
            field == OTP ||
            field == "seedPhrase" ||
            field == "privateKey" ||
            field == "pin" ||
            field.startsWith("secret:")
}

/**
 * Key shapes Keyweb gives its own meaning to, which a field name must not wear.
 *
 * `secret:` masks a value, `custom:` keeps an imported name from acting like
 * one of Keyweb's own, and `file:` points a password at an attached file.
 */
private val RESERVED_PREFIXES = listOf("secret:", "custom:", "file:")

/**
 * The key a field is stored under, given the name its owner gave it.
 *
 * One rule, in one place, because three callers need to agree on it exactly:
 * the KeePass import on each platform, and the editor where somebody types a
 * field name themselves. A field the phone stores as `secret:Answer` and the
 * browser stores as `Answer` is one field that has silently become two.
 *
 * A name colliding with one of Keyweb's own keys, or wearing one of its own
 * prefixes, is pushed under `custom:` rather than allowed to act like the real
 * thing — a field called "folder" must not move the entry, and one called
 * `file:x` must not appear under Files as an attachment that will never arrive.
 */
fun storedFieldName(name: String, secret: Boolean): ItemField {
    val collides = Fields.KNOWN.contains(name.lowercase()) ||
        RESERVED_PREFIXES.any { name.startsWith(it) }
    val safe = if (collides) "custom:$name" else name
    return if (secret) "secret:$safe" else safe
}

/**
 * What to call a field on screen.
 *
 * `secret:` and `custom:` are how a field is *stored* — one says the value is
 * masked, the other keeps an imported name from colliding with one of Keyweb's
 * own keys. Neither is the name its owner gave it, and both were being stripped
 * by hand in four places that could drift apart.
 *
 * Also what makes two keys comparable: a field the old import stored as
 * `secret:Account number` and the same field stored today as `Account number`
 * have one label between them, which is how the import recognises its own
 * earlier mistake.
 */
fun fieldLabel(field: ItemField): String =
    field.removePrefix("secret:").removePrefix("custom:")

/**
 * Fields an earlier import left under a name this one no longer uses.
 *
 * Re-importing the same file is meant to update what changed rather than make
 * a second copy, and it does — as long as both imports agree on what a field
 * is called. An earlier build did not: it stored *every* custom field as
 * `secret:<name>`, protected or not, so an account number arrived masked and
 * under a key today's import would never write. Left alone, re-importing shows
 * every one of those fields twice — once masked under the old key, once
 * correctly — and the person cannot tell which is which.
 *
 * So a field on the item whose label matches one this import is writing, under
 * a different key, is the same field wearing an old name, and is blanked. Only
 * the key dies: the value it held is kept in the item's history like any other
 * superseded write, so this is undoable rather than a deletion.
 *
 * The mirror of the web's `supersededAliases` in `keepass.ts`.
 */
fun supersededAliases(
    item: ItemRecord?,
    fresh: Map<ItemField, String>,
): Map<ItemField, String> {
    if (item == null) return emptyMap()
    val arriving = fresh.keys.mapTo(mutableSetOf()) { fieldLabel(it) }
    return buildMap {
        for ((key, register) in item.fields) {
            if (fresh.containsKey(key) || register.value.isEmpty()) continue
            if (arriving.contains(fieldLabel(key))) put(key, "")
        }
    }
}

/**
 * A value this item used to hold, for showing somebody what changed.
 *
 * The vault has kept superseded values per field since the CRDT was written —
 * that is what [HistoryEntry] is — and nothing on either platform ever showed
 * them. The KeePass import then started replaying years of somebody's earlier
 * passwords into the same place, which made an invisible feature into an
 * invisible *import result*: carried across, synced, backed up, and impossible
 * to look at.
 */
data class PastValue(
    val field: ItemField,
    /** The name to show, without the prefixes that say how it is stored. */
    val label: String,
    val value: String,
    /** Masked until asked for, on the same rule as the live field. */
    val secret: Boolean,
    /** When this value was replaced, from the HLC of the write it lost to. */
    val atMs: Long,
)

/**
 * What this item used to hold, newest first.
 *
 * Blank values are left out. A field that was empty and then filled in leaves
 * an empty entry behind, and "this used to be nothing" is not a thing anybody
 * came here to read.
 */
fun ItemRecord.pastValues(): List<PastValue> =
    history
        .filter { it.value.isNotEmpty() }
        .map {
            PastValue(
                field = it.field,
                label = fieldLabel(it.field),
                value = it.value,
                secret = Fields.isSecret(it.field),
                atMs = decodeHlc(it.ts).wall,
            )
        }
        .sortedByDescending { it.atMs }

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
    /**
     * Where this keyring's items live, when it is not the vault.
     *
     * Empty means local: the items sit in the vault document alongside every
     * other keyring's, which is how every keyring works until someone shares
     * one. A dataset id means they live in their own document, with their own
     * key, in their own Drive file.
     *
     * That split exists because Drive shares *files*, not parts of files. A
     * recipient needs access to the file the keyring is in, so a shared
     * keyring left in the vault document would hand them every other
     * keyring's ciphertext too. The reasoning in full is in
     * `docs/keyring-sharing.md`.
     *
     * Defaulted, so a vault written before this existed still decodes.
     */
    val dataset: Reg<String> = Reg("", HLC_ZERO),
)

/** Where a keyring's items are kept, or null when they are in the vault. */
fun datasetOf(keyring: KeyringRecord?): String? = keyring?.dataset?.value?.takeIf { it.isNotEmpty() }

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
    KeyringRecord(
        id = id,
        name = Reg(name, ts),
        deleted = Reg(false, HLC_ZERO),
        dataset = Reg("", HLC_ZERO),
    )

/** Items a person can actually see: not deleted, on a keyring that exists. */
/**
 * A file attached to a password, stored as an item of its own.
 *
 * Deliberately an ordinary item rather than a new kind of record. It then
 * inherits every property the vault already guarantees without a line of new
 * machinery: encrypted by the same envelope, merged by the same CRDT, carried
 * into a shared keyring's document by the same extraction, tombstoned by the
 * same delete and scrubbed by the same purge. A parallel blob store would have
 * had to re-earn all of that, and would have got some of it wrong.
 *
 * It lives on the same keyring as the password it belongs to, which is what
 * makes sharing work for nothing: sharing a keyring moves its items, and the
 * files are items.
 */
const val BLOB_KIND = "blob"

/** Items that are files rather than passwords. */
fun ItemRecord.isBlob(): Boolean = fields["kind"]?.value == BLOB_KIND

/**
 * The field on a password that points at one of its files.
 *
 * One field per file rather than a list in a single field, so attaching and
 * removing are ordinary per-field writes and two devices doing both at once
 * merge instead of overwriting each other's list.
 */
fun attachmentField(blobId: String): ItemField = "file:$blobId"

/** Every file attached to an item: the blob's id, and the name to show. */
fun ItemRecord.attachments(): List<Attachment> =
    fields.entries
        .filter { it.key.startsWith("file:") && it.value.value.isNotEmpty() }
        .map { Attachment(it.key.removePrefix("file:"), it.value.value) }
        .sortedBy { it.name }

data class Attachment(val blobId: String, val name: String)

/**
 * Every live item, wherever it says it lives. Files included.
 *
 * This used to also require the item's keyring to still exist, and that
 * requirement was a way to lose a password in total silence. A save whose
 * keyring id did not match a keyring — a stale default, a keyring deleted on
 * another device a moment earlier, a keyring record that simply has not
 * arrived yet, since an item and its keyring are separate registers that merge
 * independently — was written, published, backed up, synced to the other
 * device, and then filtered out of every screen on both. The app said "Saved"
 * and was telling the truth. Nothing was ever going to show it again.
 *
 * So membership of a keyring is no longer what grants an item the right to be
 * seen. Being live is. An item whose keyring is missing is shown as needing
 * one — see [itemsWithoutKeyring] — which is a thing somebody can fix in one
 * tap, rather than an absence they cannot even observe.
 */
fun liveItems(state: VaultState): List<ItemRecord> =
    state.items.values.filter { !it.deleted.value }

/**
 * Items a person can actually see: everything live that is not one of the
 * files hanging off another item.
 *
 * Files are excluded here rather than stored somewhere separate because this
 * is the only question they are the wrong answer to — every other part of the
 * vault should and does treat them as the ordinary items they are.
 */
fun visibleItems(state: VaultState): List<ItemRecord> =
    liveItems(state).filter { !it.isBlob() }

/**
 * Live passwords with no keyring to belong to.
 *
 * Kept as its own question so the list can say so out loud and offer to put
 * them somewhere, instead of showing a row whose keyring label is blank and
 * leaving somebody to wonder whether that means anything.
 */
fun itemsWithoutKeyring(state: VaultState): List<ItemRecord> =
    visibleItems(state).filter { item ->
        val ring = state.keyrings[item.keyring.value]
        ring == null || ring.deleted.value
    }

/** The keyrings somebody can actually put something in. */
fun liveKeyrings(state: VaultState): List<KeyringRecord> =
    state.keyrings.values.filter { !it.deleted.value }

/** What a password with no keyring is called anywhere it has to be named. */
const val NO_KEYRING: String = "Not in a keyring"

/**
 * The name to show for the keyring an item claims to be on.
 *
 * A deleted keyring is treated the same as one that was never there: its name
 * belongs to something somebody threw away, and labelling a live password with
 * it would invite them to look for it somewhere that no longer exists.
 */
fun keyringLabel(state: VaultState, keyringId: String): String {
    val ring = state.keyrings[keyringId]
    return if (ring == null || ring.deleted.value) NO_KEYRING else ring.name.value
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
            .sorted()
            .joinToString(",") { name ->
                val reg = item.fields[name]
                if (reg == null) "" else "$name=${reg.ts}:${reg.value}"
            }
        "$id|${item.keyring.ts}:${item.keyring.value}|${item.deleted.ts}:${item.deleted.value}|$fields"
    }
    val keyrings = state.keyrings.keys.sorted().joinToString(";") { id ->
        val ring = state.keyrings[id] ?: return@joinToString ""
        val base = "$id|${ring.name.ts}:${ring.name.value}|${ring.deleted.ts}:${ring.deleted.value}"
        // Appended only once the register has actually been written, so a vault
        // with no shared keyrings fingerprints byte-for-byte as it always did
        // and the cross-platform fixtures stay valid. Omitting it entirely was
        // a bug: binding a keyring to a dataset changes nothing else in the
        // vault, so the sync saw an unchanged fingerprint, skipped the upload,
        // and other devices never learned where the items had moved to.
        if (ring.dataset.ts == HLC_ZERO) base else "$base|${ring.dataset.ts}:${ring.dataset.value}"
    }
    return "v1:$items#$keyrings"
}
