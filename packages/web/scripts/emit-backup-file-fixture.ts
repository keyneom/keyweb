/*
 * Emits a locked backup file, saved by the web, for the phone's suite to open.
 *
 * The file format is shared: a file saved in a browser has to open on a phone,
 * and the other way round, or the one copy somebody kept away from Google is
 * only good on whichever device made it. The phone opens this exact file with
 * the code inside it; it writes one of its own that the web suite opens back.
 *
 * Sealed with the recovery lock the phone wrote (`sharing-recovery-lock-v1`),
 * because the phone is what writes locks. Its contents are made up.
 *
 * Regenerate with:  npm run fixture:backup --workspace @keyweb/web
 */
import { readFileSync, writeFileSync } from "node:fs";
import type {
  ProtectedSharingIdentityStore,
  ProtectedSharingIdentityV1,
} from "@keyneom/sync-kit/sharing/web-passkey";
import { applyOps, emptyVault, encodeHlc } from "@keyweb/vault-core";
import { sealBackupFile } from "../src/vault/backupFile.js";
import { KEYWEB_RECOVERY_APP_ID, unlockRecoveryIdentity } from "../src/vault/sharing/identity.js";

const lock = JSON.parse(
  readFileSync(new URL("../../../fixtures/sharing-recovery-lock-v1.json", import.meta.url), "utf8"),
) as { secretBase64: string; keyId: string; record: ProtectedSharingIdentityV1 };
const code = Uint8Array.from(Buffer.from(lock.secretBase64, "base64"));
const store: ProtectedSharingIdentityStore = {
  load: async (appId) => (appId === KEYWEB_RECOVERY_APP_ID ? lock.record : null),
  save: async () => undefined,
  delete: async () => undefined,
};
const identity = await unlockRecoveryIdentity(store, code);
if (!identity) throw new Error("The recovery lock fixture has no lock in it.");

const at = (n: number) => encodeHlc({ wall: 1_700_000_000_000 + n, counter: 0, node: "web" });
const state = applyOps(emptyVault(), [
  { kind: "keyring.put", opId: "k", ts: at(0), keyringId: "personal", name: "Just mine" },
  { kind: "keyring.bind", opId: "b", ts: at(1), keyringId: "personal", datasetId: "keyweb-abc" },
  {
    kind: "item.put",
    opId: "i",
    ts: at(2),
    itemId: "bank",
    keyringId: "personal",
    fields: { title: "Credit Union", username: "someone", password: "made-up-for-the-fixture" },
  },
]);

const file = await sealBackupFile(state, identity, lock.record, new Date("2026-09-22T00:00:00Z"));
writeFileSync(
  new URL("../../../fixtures/backup-file-web-v1.json", import.meta.url),
  JSON.stringify(
    {
      note: "Written by the web's sealBackupFile. Do not edit.",
      secretBase64: lock.secretBase64,
      keyId: lock.keyId,
      file: JSON.stringify(file),
    },
    null,
    2,
  ) + "\n",
);
console.log("Wrote fixtures/backup-file-web-v1.json");
