package app.keyweb.vault

/**
 * The folders inside a keyring.
 *
 * ## Why this is a view rather than a structure
 *
 * An item's `folder` field has always held the full nested path it came in
 * with — "Leslie / Banks" stays distinct from "Mika / Banks", and the import
 * has never collapsed them. What was missing is that nothing ever *read* it:
 * the path was preserved, synced, backed up and shown nowhere, so an organised
 * KeePass database arrived looking like one flat list of two hundred things.
 *
 * Keeping it as a field rather than promoting folders to records of their own
 * is deliberate. A folder with no identity cannot be renamed out from under an
 * item on another device, cannot be deleted while something is still in it,
 * and cannot disagree with the item about where the item is. Moving an item
 * between folders stays one ordinary per-field write that merges like every
 * other one. An empty folder simply does not exist, which is the right answer
 * for something that is only ever a way of finding things.
 *
 * ## Keyrings are not folders
 *
 * A keyring is a unit of sharing; a folder is a way of finding things. The
 * import makes the top-level group a keyring *and* leaves it at the head of
 * the path, so the first segment is dropped when it repeats the keyring's own
 * name — otherwise every vault opens onto a folder that contains everything
 * and is named after the thing you just opened.
 *
 * The mirror of the web's `folders.ts`, down to the tests.
 */

/** How a nested path is written into the one `folder` field. */
private const val FOLDER_SEPARATOR = " / "

/** This item's folder path, relative to the keyring it is on. */
fun folderPath(item: ItemRecord, state: VaultState): List<String> {
    val raw = item.fields[Fields.FOLDER]?.value ?: ""
    val segments = raw.split(FOLDER_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
    val keyring = state.keyrings[item.keyring.value]?.name?.value
    return if (keyring != null && segments.firstOrNull() == keyring) segments.drop(1) else segments
}

/**
 * This item's path when the list is showing more than one keyring.
 *
 * The keyring goes in front, and that is the whole correction. Browsing every
 * keyring at once while computing each path relative to its *own* keyring put
 * Leslie's "Banks" and Mika's "Banks" side by side under the same name — and
 * then merged them, because at that level they are the same name. The data had
 * never collided; the view invented the collision, which is worse, because the
 * two passwords looked like they were in one place.
 *
 * So across keyrings the first level *is* the keyring. Inside one, it is not,
 * because a keyring containing a single folder named after itself is a level
 * nobody wants to walk through.
 */
fun folderPathAcrossKeyrings(item: ItemRecord, state: VaultState): List<String> {
    // A password whose keyring is gone gets a level of its own rather than
    // being tipped out at the top among the keyring rows, where it would look
    // like one more keyring and give no hint that anything wanted fixing.
    return listOf(keyringLabel(state, item.keyring.value)) + folderPath(item, state)
}

/** Whether a level is a keyring rather than a folder somebody made. */
enum class FolderKind { KEYRING, FOLDER }

data class FolderChild(
    val name: String,
    /** The full path to this folder, for descending into it. */
    val path: List<String>,
    /** Everything beneath it, not only what sits directly inside. */
    val count: Int,
    /**
     * Carried so the row can be drawn as the keyring it is — the same colour
     * as its chip — instead of wearing a folder icon and implying it is the
     * kind of thing you could rename or nest.
     */
    val kind: FolderKind = FolderKind.FOLDER,
)

data class FolderView(val folders: List<FolderChild>, val items: List<ItemRecord>)

/**
 * What to show at one place in the tree.
 *
 * Folders count recursively, because a folder showing "0" that opens onto
 * three subfolders full of passwords is a folder nobody opens.
 */
fun browseFolders(
    items: List<ItemRecord>,
    state: VaultState,
    at: List<String>,
    /**
     * The keyring the list is filtered to, or null when it is showing all of
     * them. Null is what puts the keyring at the front of every path.
     */
    keyringId: String? = null,
): FolderView {
    val pathOf: (ItemRecord) -> List<String> =
        if (keyringId == null) {
            { folderPathAcrossKeyrings(it, state) }
        } else {
            { folderPath(it, state) }
        }

    val here = mutableListOf<ItemRecord>()
    val counts = linkedMapOf<String, Int>()

    for (item in items) {
        val path = pathOf(item)
        if (!path.startsWith(at)) continue
        if (path.size == at.size) {
            here += item
            continue
        }
        val next = path[at.size]
        counts[next] = (counts[next] ?: 0) + 1
    }

    // At the top of an unfiltered list every child is a keyring; one level
    // down it is a folder inside one.
    val kind = if (keyringId == null && at.isEmpty()) FolderKind.KEYRING else FolderKind.FOLDER

    val folders = counts.entries
        .map { FolderChild(it.key, at + it.key, it.value, kind) }
        .sortedBy { it.name.lowercase() }

    return FolderView(folders, here)
}

/** Every folder that exists anywhere in these items, for a picker. */
fun allFolders(items: List<ItemRecord>, state: VaultState): List<List<String>> {
    val seen = linkedMapOf<String, List<String>>()
    for (item in items) {
        val path = folderPath(item, state)
        for (depth in 1..path.size) {
            val slice = path.take(depth)
            seen[slice.joinToString(FOLDER_SEPARATOR)] = slice
        }
    }
    return seen.values.sortedBy { it.joinToString(FOLDER_SEPARATOR).lowercase() }
}

private fun List<String>.startsWith(prefix: List<String>): Boolean {
    if (size < prefix.size) return false
    return prefix.withIndex().all { (index, part) -> this[index] == part }
}
