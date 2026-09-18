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

data class FolderChild(
    val name: String,
    /** The full path to this folder, for descending into it. */
    val path: List<String>,
    /** Everything beneath it, not only what sits directly inside. */
    val count: Int,
)

data class FolderView(val folders: List<FolderChild>, val items: List<ItemRecord>)

/**
 * What to show at one place in the tree.
 *
 * Folders count recursively, because a folder showing "0" that opens onto
 * three subfolders full of passwords is a folder nobody opens.
 */
fun browseFolders(items: List<ItemRecord>, state: VaultState, at: List<String>): FolderView {
    val here = mutableListOf<ItemRecord>()
    val counts = linkedMapOf<String, Int>()

    for (item in items) {
        val path = folderPath(item, state)
        if (!path.startsWith(at)) continue
        if (path.size == at.size) {
            here += item
            continue
        }
        val next = path[at.size]
        counts[next] = (counts[next] ?: 0) + 1
    }

    val folders = counts.entries
        .map { FolderChild(it.key, at + it.key, it.value) }
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
