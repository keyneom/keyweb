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
 * The vault as a person sees it: its own items, plus those of every dataset.
 *
 * A plain merge, so the CRDT's guarantees carry across documents unchanged.
 * Datasets are joined in a fixed order so two devices assembling the same set
 * of documents produce identical state, which the fingerprint depends on.
 */
fun composeVault(vault: VaultState, datasets: Map<String, VaultState>): VaultState {
    var composed = vault
    for (id in datasets.keys.sorted()) composed = mergeVaults(composed, datasets.getValue(id))
    return composed
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
