import { DriveAppDataProtectedSharingIdentityStore } from "@keyneom/sync-kit/sharing/appdata-identity-store";
import { IndexedDbProtectedSharingIdentityStore } from "@keyneom/sync-kit/sharing/web-passkey";
import { authorizeGoogle } from "../googleAuth";
import { KeywebSharingIdentityStore, SharingIdentity } from "./identity";

export * from "./identity";

/**
 * The sharing identity as the app actually uses it.
 *
 * Authoritative copy in the Google account's hidden app-data folder, cached
 * copy in this browser — see `identity.ts` for why that order and not the
 * other one.
 */
export function createSharingIdentity(secret: () => Promise<Uint8Array>): SharingIdentity {
  return new SharingIdentity({
    store: new KeywebSharingIdentityStore({
      remote: new DriveAppDataProtectedSharingIdentityStore({
        authorization: () => authorizeGoogle(),
      }),
      local: new IndexedDbProtectedSharingIdentityStore({ databaseName: "keyweb-sharing" }),
    }),
    secret,
  });
}

export * from "./controller";
export * from "./links";
export * from "./operations";
export * from "./picker";
