/*
 * Emits a sharing identity, wrapped by the recovery secret, from the web.
 *
 * The Kotlin port unwraps this exact record with the same secret and must
 * arrive at the same key id. That is the only honest way to claim one person
 * is one participant across their phone and their browser — and if it is not
 * true, they cannot open the keyrings they shared themselves, which is a
 * failure with no error message attached to it.
 *
 * Both being "HKDF then AES-GCM" proves nothing: the HKDF label, the salt, the
 * AAD over the record header and the packing of the two private keys all have
 * to agree as well.
 *
 * The secret is fixed rather than random so regenerating is stable. It protects
 * nothing — this identity is thrown away.
 *
 * Regenerate with:  npm run fixture:sharing --workspace @keyweb/web
 */
import { writeFileSync } from "node:fs";
import { SharingIdentity } from "../src/vault/sharing/identity.js";
import type {
  ProtectedSharingIdentityStore,
  ProtectedSharingIdentityV1,
} from "@keyneom/sync-kit/sharing/web-passkey";

const SECRET = new Uint8Array([
  0x3a, 0x91, 0x0c, 0x7d, 0xe2, 0x45, 0xb8, 0x16, 0xf0, 0x29, 0x5c, 0xa3, 0x71, 0xd4, 0x68, 0x0b,
  0x9e, 0x37, 0xc2, 0x50,
]);

let saved: ProtectedSharingIdentityV1 | null = null;
const store: ProtectedSharingIdentityStore = {
  async load() {
    return saved;
  },
  async save(record) {
    saved = record;
  },
  async delete() {
    saved = null;
  },
};

const identity = await new SharingIdentity({ store, secret: async () => SECRET }).getOrCreate();

writeFileSync(
  new URL("../../../fixtures/sharing-identity-v1.json", import.meta.url),
  `${JSON.stringify(
    {
      note: "Written by packages/web/scripts/emit-sharing-identity-fixture.ts. Do not edit.",
      secretBase64: Buffer.from(SECRET).toString("base64"),
      keyId: identity.publicKey.keyId,
      fingerprint: SharingIdentity.fingerprint(identity),
      record: saved,
    },
    null,
    2,
  )}\n`,
);
console.log(`sharing identity fixture: ${SharingIdentity.fingerprint(identity)}`);
