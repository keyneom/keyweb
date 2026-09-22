package app.keyweb.vault

/**
 * Splitting a keyring out of the vault, and putting it back.
 *
 * A shared keyring lives in its own document so that sharing it shares only
 * it — the reasoning is in `docs/keyring-sharing.md`, and the short version is
 * that Drive shares files rather than parts of files, so a keyring left in the
 * vault document would take every other keyring's ciphertext with it.
 *
 * Pure functions over states, matching the web's `datasets.ts` decision for
 * decision. Nothing here writes, encrypts or talks to Drive: moving items
 * between two documents belongs to storage, which is the only layer that can
 * do it in one transaction.
 */

/** One keyring and its items, as its own document. */
fun extractDataset(vault: VaultState, keyringId: String): VaultState {
    val keyring = vault.keyrings[keyringId] ?: return emptyVault()
    // Tombstones travel too. A deleted password left behind in the vault would
    // be resurrected the next time the documents were joined, because the
    // dataset would have no record of it ever existing.
    val items = vault.items.filterValues { it.keyring.value == keyringId }
    return VaultState(items = items, keyrings = mapOf(keyringId to keyring))
}

/**
 * The vault with a keyring's items removed, the keyring itself kept.
 *
 * The keyring record stays because it is what says the keyring exists, what it
 * is called, and where its items went.
 */
fun withoutDatasetItems(vault: VaultState, keyringId: String): VaultState =
    vault.copy(items = vault.items.filterValues { it.keyring.value != keyringId })

/**
 * What a shared document is allowed to contribute to the composed vault.
 *
 * A shared file is a complete [VaultState], so a collaborator can put
 * anything in it: a `personal` keyring, a later binding, an item that
 * collides with one of ours. Merging that in unsandboxed is how private
 * passwords get routed into someone else's Drive file.
 *
 * The vault already recorded which keyring this dataset is. Only that
 * keyring, under that id, and items already on it, come through. Bindings
 * stay the vault's: a shared document must not be able to move them.
 */
fun sandboxDataset(
    keyringId: String,
    dataset: VaultState,
    vault: VaultState = emptyVault(),
    /**
     * The id this vault bound, when it differs from [keyringId].
     *
     * An adopted share keeps the shared document's id in the file and a fresh
     * id locally. Items already in this vault belong to the local id, not the
     * source id, even when those strings match — that match is the `"personal"`
     * collision.
     */
    localKeyringId: String = keyringId,
): VaultState {
    val keyring = dataset.keyrings[keyringId]
    val items = dataset.items.filter { (id, item) ->
        if (item.keyring.value != keyringId) return@filter false
        val ours = vault.items[id]
        // An item this vault already holds on a different keyring is ours,
        // not theirs. Merging the two would let a later HLC on their copy
        // rebind the register and route the next save into their file.
        ours == null || ours.keyring.value == localKeyringId
    }
    return VaultState(
        items = items,
        keyrings = if (keyring != null) mapOf(keyringId to keyring) else emptyMap(),
    )
}

/**
 * Show a shared document's keyring under the id this vault bound.
 *
 * The bytes in the file keep the source id. Only this device's composed view
 * uses the local id. Writing back translates the other way.
 */
private fun presentAs(dataset: VaultState, sourceId: String, localId: String): VaultState {
    if (sourceId == localId) return dataset
    val ring = dataset.keyrings[sourceId]
    val keyrings = dataset.keyrings.toMutableMap()
    keyrings.remove(sourceId)
    if (ring != null) keyrings[localId] = ring.copy(id = localId)
    val items = dataset.items.mapValues { (_, item) ->
        if (item.keyring.value == sourceId) {
            item.copy(keyring = item.keyring.copy(value = localId))
        } else {
            item
        }
    }
    return VaultState(items = items, keyrings = keyrings)
}

/**
 * The vault as a person sees it: its own items, plus those of every dataset.
 *
 * Datasets are joined in a fixed order so two devices assembling the same set
 * of documents produce identical state, which the fingerprint depends on.
 *
 * Each dataset is sandboxed to the keyring the *vault* bound to it before
 * the merge. A plain [mergeVaults] of the raw documents would treat a
 * collaborator's file as if it were our vault, which it is not.
 */
fun composeVault(vault: VaultState, datasets: Map<String, VaultState>): VaultState {
    val expected = boundDatasets(vault).associate { it.datasetId to it.keyringId }
    var composed = vault
    for (id in datasets.keys.sorted()) {
        val keyringId = expected[id] ?: continue
        val sourceId = sourceKeyringId(vault.keyrings[keyringId], keyringId)
        composed = mergeVaults(
            composed,
            presentAs(
                sandboxDataset(sourceId, datasets.getValue(id), vault, keyringId),
                sourceId,
                keyringId,
            ),
        )
    }
    // Bindings are this device's record of where items live. Restore them
    // from the vault after the merge so a later register in a shared file
    // cannot re-route writes.
    val keyrings = composed.keyrings.toMutableMap()
    for ((id, ring) in vault.keyrings) {
        val current = keyrings[id] ?: continue
        keyrings[id] = current.copy(dataset = ring.dataset, source = ring.source)
    }
    return composed.copy(keyrings = keyrings)
}

/**
 * Which dataset an operation belongs to, or null for the vault.
 *
 * Every write has to be routed: an edit to a password in a shared keyring
 * belongs in that keyring's document, and putting it in the vault instead
 * would mean the other person never saw it while this device looked correct.
 */
fun datasetForItem(composed: VaultState, keyringId: String?): String? =
    keyringId?.let { datasetOf(composed.keyrings[it]) }

/** A keyring bound to its own document. */
data class DatasetBinding(val keyringId: String, val datasetId: String)

/** Every dataset the vault currently expects to be able to read. */
fun boundDatasets(vault: VaultState): List<DatasetBinding> =
    vault.keyrings.values
        .filter { !it.deleted.value }
        .mapNotNull { keyring ->
            datasetOf(keyring)?.let { DatasetBinding(keyring.id, it) }
        }
        .sortedBy { it.datasetId }
