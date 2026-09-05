/*
 * Emits a canonical vault payload from the TypeScript implementation.
 *
 * The Kotlin port reads this exact file back and must agree on both the parsed
 * content and the fingerprint. Fingerprint equality is what decides whether a
 * sync skips an upload, so a divergence there would make one platform
 * repeatedly republish work the other considers already published.
 *
 * Regenerate with:  npm run fixture --workspace @keyweb/vault-core
 */
import { writeFileSync } from "node:fs";
import { createClock } from "../src/hlc.js";
import { emptyVault, fingerprint } from "../src/model.js";
import { applyOps, type VaultOp } from "../src/ops.js";

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
    fields: { title: "Chase Bank", username: "maria@example.com", password: "original" },
  },
  // Supersede a password so the fixture carries a history entry too.
  {
    kind: "item.put",
    opId: id(),
    ts: clock.now(),
    itemId: "chase",
    keyringId: "ring",
    fields: { password: "rotated" },
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
const payload = { state, fingerprint: fingerprint(state) };
const target = new URL("../../../fixtures/wire-v1.json", import.meta.url);
writeFileSync(target, `${JSON.stringify(payload, null, 2)}\n`);
console.log(`wrote ${target.pathname}`);
console.log(`fingerprint: ${payload.fingerprint}`);
