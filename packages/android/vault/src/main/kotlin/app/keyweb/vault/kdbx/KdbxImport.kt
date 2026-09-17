package app.keyweb.vault.kdbx

import app.keyweb.vault.BLOB_KIND
import app.keyweb.vault.Fields
import app.keyweb.vault.HlcParts
import app.keyweb.vault.ItemField
import app.keyweb.vault.Stamp
import app.keyweb.vault.VaultOp
import app.keyweb.vault.VaultState
import app.keyweb.vault.attachmentField
import app.keyweb.vault.decodeHlc
import app.keyweb.vault.encodeHlc
import app.keyweb.vault.supersededAliases

/**
 * Turning a read KeePass file into the writes that land it in the vault.
 *
 * Deliberately a pure function in the vault module rather than a loop inside
 * the view model, and deliberately the mirror of the web's `importOperations`.
 * The two platforms import the same file into the same vault, so what they
 * *write* has to agree as exactly as what they read — and the reader had a
 * fidelity test pinning it while this half had nothing at all. Every bug this
 * file now guards against reached a real phone first.
 */
fun importOperations(
    entries: List<KdbxEntry>,
    /** Keyring id per top-level group name, prepared by the caller. */
    keyringIds: Map<String, String>,
    /** Where entries in no group go, as the person chose. */
    ungroupedKeyringId: String,
    /** The vault as it stands, so an earlier import's names can be retired. */
    existing: VaultState,
    stamp: () -> Stamp,
): List<VaultOp> = buildList {
    for (entry in entries) {
        // An entry either names a group, which the caller has mapped, or names
        // none and goes where the caller said. There is no third case and
        // deliberately no fallback: a missing mapping used to be absorbed
        // silently, which is how entries ended up in folders nobody put them in.
        val keyringId = entry.keyringName?.let(keyringIds::get) ?: ungroupedKeyringId
        check(keyringId.isNotEmpty()) { "No keyring was prepared for \"${entry.keyringName}\"." }

        val (opId, ts) = stamp()
        val itemId = "kdbx:${entry.uuid}"

        /*
         * Each earlier version is replayed as the edit it actually was,
         * stamped at the moment KeePass recorded it.
         *
         * Nothing translates between the two history shapes: the CRDT already
         * keeps a superseded value whenever a later write replaces it, so
         * replaying old then new produces exactly the history this vault would
         * have had if the entry had been edited here all along.
         *
         * The timestamps are built rather than taken from the clock. Asking a
         * clock for 2019 is impossible, and *observing* a KeePass file whose
         * clock reads 2099 would pin this vault's clock forever — so they are
         * clamped below the stamp this import is landing at.
         */
        val base = if (entry.versions.isEmpty()) null else decodeHlc(ts)
        entry.versions.forEachIndexed { index, version ->
            if (base == null) return@forEachIndexed
            val fields = kdbxFieldsToItemFields(version.fields, version.protectedKeys)
            if (fields.isEmpty()) return@forEachIndexed
            add(
                VaultOp.ItemPut(
                    opId = "$opId:v$index",
                    ts = encodeHlc(
                        HlcParts(
                            wall = minOf(version.atMs, base.wall - 1),
                            counter = index,
                            node = base.node,
                        ),
                    ),
                    itemId = itemId,
                    keyringId = keyringId,
                    fields = fields,
                ),
            )
        }

        /*
         * Each attached file becomes an item of its own on the same keyring,
         * and the password gains a field pointing at it — the same shape as a
         * file attached by hand, because it is the same thing. The blob ops
         * come first so a device replaying the outbox never sees a password
         * referring to bytes that have not arrived yet.
         */
        val files = entry.attachments.filter { it.data.isNotEmpty() }
        for (attached in files) {
            add(
                VaultOp.ItemPut(
                    opId = "$opId:${attached.blobId}",
                    ts = ts,
                    itemId = attached.blobId,
                    keyringId = keyringId,
                    fields = mapOf(
                        "kind" to BLOB_KIND,
                        "name" to attached.name,
                        "type" to guessAttachmentType(attached.name),
                        "size" to attached.bytes.toString(),
                        "secret:data" to attached.data,
                    ),
                ),
            )
        }

        val fields = entry.toFields() +
            files.associate { attachmentField(it.blobId) to it.name }
        add(
            VaultOp.ItemPut(
                opId = opId,
                ts = ts,
                itemId = itemId,
                keyringId = keyringId,
                // Names an earlier import gave these same fields go first, so
                // the one this import writes wins the key.
                fields = supersededAliases(existing.items[itemId], fields) + fields,
            ),
        )
    }
}

/**
 * Every field of an entry, under the names Keyweb stores them by.
 *
 * The naming rule lives beside the reader because the web reader has to agree
 * with it exactly: the same file imported on a phone and in a browser lands in
 * the same vault, and a field called `secret:Answer` on one side and `Answer`
 * on the other is one field that has become two.
 */
fun KdbxEntry.toFields(): Map<ItemField, String> = buildMap {
    putAll(kdbxFieldsToItemFields(allFields(), protectedKeys))
    // An entry with nothing in it is never imported, so a title always exists
    // by the time anyone looks at one.
    if (title.isEmpty()) put(Fields.TITLE, "Untitled")
    if (folder.isNotEmpty()) put(Fields.FOLDER, folder)
    if (tags.isNotEmpty()) put(Fields.TAGS, tags)
}

/**
 * Enough to decide whether a viewer can show it; the name is the only clue
 * KeePass gives. Matches the web's guess so the same file gets the same type.
 */
fun guessAttachmentType(name: String): String =
    when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }
