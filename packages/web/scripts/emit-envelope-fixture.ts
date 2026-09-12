/*
 * Emits an encrypted backup envelope from the TypeScript implementation.
 *
 * The Kotlin port opens this exact file with the recovery code printed beside
 * it. That is the only honest way to claim the two platforms interoperate:
 * both being "AES-GCM" proves nothing, because the AAD, the HKDF label, the
 * salt handling and the gzip flag all have to agree as well.
 *
 * The secret is fixed rather than random so regenerating produces a stable
 * code. It protects nothing — this fixture holds an invented vault.
 *
 * Regenerate with:  npm run fixture --workspace @keyweb/web
 */
import { writeFileSync } from "node:fs";
import { createClock } from "@keyweb/vault-core";
import { applyOps, emptyVault, fingerprint, type VaultOp } from "@keyweb/vault-core";
import { createRecoveryCipher } from "../src/vault/crypto.js";
import { formatRecoveryCode } from "../src/vault/recovery.js";

const SECRET = new Uint8Array([
  0x3a, 0x91, 0x0c, 0x7d, 0xe2, 0x45, 0xb8, 0x16, 0xf0, 0x29, 0x5c, 0xa3, 0x71, 0xd4, 0x68, 0x0b,
  0x9e, 0x37, 0xc2, 0x50,
]);

const clock = createClock({ node: "fixture", physical: () => 1_700_000_000_000 });
let n = 0;
const id = () => `op-${++n}`;

const ops: VaultOp[] = [
  { kind: "keyring.put", opId: id(), ts: clock.now(), keyringId: "ring", name: "Household" },
  {
    kind: "item.put",
    opId: id(),
    ts: clock.now(),
    itemId: "chase",
    keyringId: "ring",
    fields: {
      title: "Chase Bank",
      username: "maria@example.com",
      password: "the-one-that-matters",
      folder: "Banking / Personal",
      tags: "finance, important",
    },
  },
  {
    kind: "item.put",
    opId: id(),
    ts: clock.now(),
    itemId: "netflix",
    keyringId: "ring",
    fields: { title: "Netflix", password: "shared-one" },
  },
  { kind: "item.delete", opId: id(), ts: clock.now(), itemId: "netflix" },
];

const state = applyOps(emptyVault(), ops);
const cipher = await createRecoveryCipher(SECRET, undefined, "keyweb");

const payload = {
  note: "Sealed by the TypeScript implementation. Kotlin must open it with the code below.",
  recoveryCode: formatRecoveryCode(SECRET),
  fingerprint: fingerprint(state),
  envelope: await cipher.sealState(state),
};

const target = new URL("../../../fixtures/envelope-web-v1.json", import.meta.url);
writeFileSync(target, `${JSON.stringify(payload, null, 2)}\n`);
console.log(`wrote ${target.pathname}`);
console.log(`recovery code: ${payload.recoveryCode}`);
