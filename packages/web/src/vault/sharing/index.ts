import { DriveAppDataProtectedSharingIdentityStore } from "@keyneom/sync-kit/sharing/appdata-identity-store";
import {
  IndexedDbProtectedSharingIdentityStore,
  PasskeyProtectedSharingIdentityProvider,
} from "@keyneom/sync-kit/sharing/web-passkey";
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

export * from "./controller";
export * from "./links";
export * from "./operations";
export * from "./picker";
