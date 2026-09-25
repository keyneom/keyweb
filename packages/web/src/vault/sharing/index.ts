import { DriveAppDataProtectedSharingIdentityStore } from "@keyneom/sync-kit/sharing/appdata-identity-store";
import {
  createProtectedSharingIdentityV1,
  IndexedDbProtectedSharingIdentityStore,
  PasskeyProtectedSharingIdentityProvider,
} from "@keyneom/sync-kit/sharing/web-passkey";
import { boundDatasets } from "@keyweb/vault-core";
import { createWebPasskeyProvider } from "@keyneom/sync-kit/keys/web-passkey";
import { authorizeGoogle } from "../googleAuth";
import { keywebRpId } from "../profile";
import type { WebCryptoSharingIdentity } from "@keyneom/sync-kit/sharing/web-crypto";
import {
  KEYWEB_SHARING_APP_ID,
  KeywebSharingIdentityStore,
  keywebSharingProfile,
  type SharingIdentityLike,
} from "./identity";
import { createKeywebSharingController, INDEX_DATASET_ID } from "./controller";
import { participantRecoveryCode, recoverWithParticipantKey } from "./recoveryKeys";

export * from "./identity";

/**
 * The sharing identity as the app actually uses it.
 *
 * Authoritative copy in the Google account's hidden app-data folder, cached
 * copy in this browser — see `identity.ts` for why that order and not the
 * other one — and wrapped with the passkey, which is the part that was wrong.
 *
 * It was wrapped with the printed recovery code instead, on the reasoning that
 * the phone had no passkey. It has one, and sync-kit ships this provider and
 * this store precisely so one account carries one identity to all its devices;
 * easy-bc has used both from the start. Keyweb had the store and not the key.
 */
/** The app-data store the identity records live in, both locks side by side. */
export function sharingIdentityStore(): KeywebSharingIdentityStore {
  return new KeywebSharingIdentityStore({
    remote: new DriveAppDataProtectedSharingIdentityStore({
      authorization: () => authorizeGoogle(),
    }),
    local: new IndexedDbProtectedSharingIdentityStore({ databaseName: "keyweb-sharing" }),
  });
}

/**
 * The identity for this session.
 *
 * Handed one already unlocked by the printed code when the passkey is what was
 * lost; otherwise the passkey unlocks it as on every other device.
 */
export function createSharingIdentity(recovered?: WebCryptoSharingIdentity | null): SharingIdentityLike {
  const provider = createPasskeyIdentity();
  if (!recovered) return provider;
  return {
    getOrCreate: async () => recovered,
    clear: () => provider.clear(),
  };
}

function createPasskeyIdentity(): PasskeyProtectedSharingIdentityProvider {
  return new PasskeyProtectedSharingIdentityProvider({
    appId: KEYWEB_SHARING_APP_ID,
    // The same passkey that opens the vault, through the sharing profile whose
    // HKDF label both platforms share — so the phone and this browser derive
    // one identity rather than one each.
    passkeyProvider: createWebPasskeyProvider(keywebSharingProfile, { rpId: keywebRpId() }),
    store: new KeywebSharingIdentityStore({
      remote: new DriveAppDataProtectedSharingIdentityStore({
        authorization: () => authorizeGoogle(),
      }),
      local: new IndexedDbProtectedSharingIdentityStore({ databaseName: "keyweb-sharing" }),
    }),
  });
}

/**
 * Become a participant again from the printed code, with the passkey gone.
 *
 * For an account whose code was never written as a lock — one set up in a
 * browser, with no phone — so the only thing the code opens is the recovery
 * key on your files. The code is checked against the index first, so a wrong
 * one asks for no passkey and changes nothing. Then a new passkey is made, a
 * new key under it, and the recovery key signs that key in wherever the old
 * one was. Saved last, once the index already names it.
 *
 * Null when the code opens no recovery key in this account.
 */
export async function recoverSharingIdentityWithCode(
  secret: Uint8Array,
): Promise<WebCryptoSharingIdentity | null> {
  const nobody: SharingIdentityLike = {
    getOrCreate: () => Promise.reject(new Error("No sharing key is open yet.")),
    clear: () => undefined,
  };
  const probe = createKeywebSharingController(nobody);
  const code = await participantRecoveryCode(secret);
  const opens = await probe.openRecoveryKey({ datasetId: INDEX_DATASET_ID, code }).then(
    () => true,
    () => false,
  );
  if (!opens) return null;

  const passkeyProvider = createWebPasskeyProvider(keywebSharingProfile, { rpId: keywebRpId() });
  const created = await passkeyProvider.create();
  const replacement = await createProtectedSharingIdentityV1(
    KEYWEB_SHARING_APP_ID,
    created.metadata,
    created.key,
  );
  const controller = createKeywebSharingController({
    getOrCreate: async () => replacement.identity,
    clear: () => undefined,
  });
  await recoverWithParticipantKey({
    controller,
    secret,
    replacement: replacement.identity,
    indexDatasetId: INDEX_DATASET_ID,
    datasetsAfterIndex: async () => {
      const { value } = await controller.loadDataset(INDEX_DATASET_ID);
      return [...new Set(boundDatasets(value).map((binding) => binding.datasetId))];
    },
  });
  await sharingIdentityStore().save(replacement.record);
  return replacement.identity;
}

export * from "./controller";
export * from "./links";
export * from "./operations";
export * from "./picker";
export * from "./recoveryKeys";
