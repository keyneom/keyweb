package app.keyweb.vault

/**
 * How a list is ordered, as one choice rather than two.
 *
 * Deliberately not a field plus an ascending/descending arrow. That is two
 * controls to understand and a state ("descending") that means nothing until
 * you also know what it applies to — is descending by name A–Z or Z–A? Naming
 * both ends of each ordering says exactly what will happen before it happens,
 * which is the whole point of offering the control.
 *
 * Shared between platforms so a vault sorted on a phone and the same vault
 * sorted in a browser put the same thing at the top.
 */
enum class ItemSort(val id: String, val label: String) {
    NAME_AZ("name-az", "Name (A–Z)"),
    NAME_ZA("name-za", "Name (Z–A)"),
    NEWEST("newest", "Changed most recently"),
    OLDEST("oldest", "Unchanged the longest"),
    ;

    companion object {
        fun of(id: String?): ItemSort = entries.firstOrNull { it.id == id } ?: NAME_AZ
    }
}

enum class KeyringSort(val id: String, val label: String) {
    NAME_AZ("name-az", "Name (A–Z)"),
    NAME_ZA("name-za", "Name (Z–A)"),
    MOST("most", "Most passwords first"),
    FEWEST("fewest", "Fewest passwords first"),
    ;

    companion object {
        fun of(id: String?): KeyringSort = entries.firstOrNull { it.id == id } ?: NAME_AZ
    }
}

/**
 * When this item last changed, from the writes it carries.
 *
 * Read off the CRDT rather than stored as a field. Every register already
 * knows the HLC of the write that produced it, so the newest of them is the
 * moment the item last changed — and it stays correct through a merge, because
 * the merge is what picks those registers in the first place. A separate
 * `updatedAt` field would be a second source of truth that two devices could
 * disagree about.
 *
 * `folder`, `tags` and the file pointers count too: moving a password into a
 * folder is a change to it, and so is attaching a scan.
 */
fun ItemRecord.lastChangedAt(): Long {
    var newest = decodeHlc(keyring.ts).wall
    for (register in fields.values) {
        val wall = decodeHlc(register.ts).wall
        if (wall > newest) newest = wall
    }
    return newest
}

/** A stable order: ties break on the name, then the id, so it never shuffles. */
private fun ItemRecord.sortName(): String = (fields["title"]?.value ?: "").lowercase()

fun sortItems(items: List<ItemRecord>, sort: ItemSort): List<ItemRecord> {
    val byName = compareBy<ItemRecord>({ it.sortName() }, { it.id })
    return when (sort) {
        ItemSort.NAME_AZ -> items.sortedWith(byName)
        ItemSort.NAME_ZA -> items.sortedWith(byName.reversed())
        ItemSort.NEWEST -> items.sortedWith(
            compareByDescending<ItemRecord> { it.lastChangedAt() }.then(byName),
        )
        ItemSort.OLDEST -> items.sortedWith(
            compareBy<ItemRecord> { it.lastChangedAt() }.then(byName),
        )
    }
}

fun sortKeyrings(
    keyrings: List<KeyringRecord>,
    /** How many passwords each one holds, by keyring id. */
    counts: Map<String, Int>,
    sort: KeyringSort,
): List<KeyringRecord> {
    val byName = compareBy<KeyringRecord>({ it.name.value.lowercase() }, { it.id })
    fun size(ring: KeyringRecord) = counts[ring.id] ?: 0
    return when (sort) {
        KeyringSort.NAME_AZ -> keyrings.sortedWith(byName)
        KeyringSort.NAME_ZA -> keyrings.sortedWith(byName.reversed())
        KeyringSort.MOST -> keyrings.sortedWith(
            compareByDescending<KeyringRecord> { size(it) }.then(byName),
        )
        KeyringSort.FEWEST -> keyrings.sortedWith(
            compareBy<KeyringRecord> { size(it) }.then(byName),
        )
    }
}
