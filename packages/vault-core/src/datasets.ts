import { datasetOf, emptyVault, type VaultState } from "./model.js";
import { mergeVaults } from "./merge.js";

/**
 * Splitting a keyring out of the vault, and putting it back.
 *
 * A shared keyring lives in its own document so that sharing it shares only
 * it — the reasoning is in `docs/keyring-sharing.md`, and the short version is
 * that Drive shares files rather than parts of files, so a keyring left in the
 * vault document would take every other keyring's ciphertext with it.
 *
 * Everything here is a pure function over states. Nothing writes, nothing
 * encrypts and nothing talks to Drive: those belong to the storage layer,
 * which is the only place that can move items between two documents in one
 * transaction. Keeping the decision pure is what makes it testable, and this
 * is the change that is expensive to get wrong.
 */

/** One keyring and its items, as its own document. */
export function extractDataset(vault: VaultState, keyringId: string): VaultState {
  const keyring = vault.keyrings[keyringId];
  if (!keyring) return emptyVault();

  const items = Object.fromEntries(
    Object.entries(vault.items).filter(([, item]) => item.keyring.value === keyringId),
  );
  // Tombstones travel too. A deleted password that stayed behind in the vault
  // would be resurrected the next time the two documents were joined, because
  // the dataset would have no record of it ever existing.
  return { keyrings: { [keyringId]: keyring }, items };
}

/**
 * The vault with a keyring's items removed, the keyring itself kept.
 *
 * The keyring record stays because it is what says the keyring exists, what
 * it is called, and where its items went. Removing it would leave the vault
 * unable to name what it no longer holds.
 */
export function withoutDatasetItems(vault: VaultState, keyringId: string): VaultState {
  const items = Object.fromEntries(
    Object.entries(vault.items).filter(([, item]) => item.keyring.value !== keyringId),
  );
  return { ...vault, items };
}

/**
 * The vault as a person sees it: its own items, plus those of every dataset.
 *
 * A plain merge, so the CRDT's guarantees carry across documents unchanged —
 * joining is commutative and idempotent whether the two sides came from one
 * file or two.
 *
 * Datasets are joined in a fixed order (by id) so that two devices assembling
 * the same set of documents produce byte-identical state, which the sync
 * engine's fingerprint depends on.
 */
export function composeVault(
  vault: VaultState,
  datasets: ReadonlyMap<string, VaultState>,
): VaultState {
  let composed = vault;
  for (const id of [...datasets.keys()].sort()) {
    composed = mergeVaults(composed, datasets.get(id)!);
  }
  return composed;
}

/**
 * Which dataset an operation belongs to, or null for the vault.
 *
 * Every write has to be routed: an edit to a password in a shared keyring
 * belongs in that keyring's document, and putting it in the vault instead
 * would mean the other person never saw it while this device looked correct.
 */
export function datasetForItem(
  composed: VaultState,
  keyringId: string | undefined,
): string | null {
  if (!keyringId) return null;
  return datasetOf(composed.keyrings[keyringId]);
}

/** Every dataset the vault currently expects to be able to read. */
export function boundDatasets(vault: VaultState): { keyringId: string; datasetId: string }[] {
  return Object.values(vault.keyrings)
    .filter((keyring) => !keyring.deleted.value)
    .flatMap((keyring) => {
      const datasetId = datasetOf(keyring);
      return datasetId ? [{ keyringId: keyring.id, datasetId }] : [];
    })
    .sort((a, b) => a.datasetId.localeCompare(b.datasetId));
}
