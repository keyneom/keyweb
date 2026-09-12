/*
 * Emits the item-kind catalogue as the TypeScript side defines it.
 *
 * The Kotlin port compares itself against this. Field keys are a
 * cross-platform contract: a seed phrase written under `seedPhrase` on the
 * phone and `seed_phrase` in the browser would be two separate fields, and half
 * of someone's wallet backup would appear to vanish depending which app they
 * opened. A comment asking both sides to stay aligned would not have caught it.
 *
 * Regenerate with:  npm run fixture:kinds --workspace @keyweb/vault-core
 */
import { writeFileSync } from "node:fs";
import { ITEM_KINDS } from "../src/kinds.js";

const payload = {
  note: "Generated from packages/vault-core/src/kinds.ts. Kotlin must match.",
  kinds: ITEM_KINDS.map((kind) => ({
    id: kind.id,
    name: kind.name,
    summary: kind.summary,
    fields: kind.fields.map((field) => ({
      key: field.key,
      label: field.label,
      shape: field.shape,
    })),
  })),
};

const target = new URL("../../../fixtures/kinds-v1.json", import.meta.url);
writeFileSync(target, `${JSON.stringify(payload, null, 2)}\n`);
console.log(`wrote ${target.pathname} (${payload.kinds.length} kinds)`);
