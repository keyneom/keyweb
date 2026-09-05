import { defineV1CompatibilityProfile } from "@keyneom/sync-kit/crypto";

/**
 * Keyweb owns these persisted values. They define the cryptographic context for
 * every envelope the app writes — the AAD, the HKDF label, the filename, and
 * the passkey presentation.
 *
 * Changing any of them makes existing vaults unreadable, so they must stay
 * aligned with the Android implementation and can only move behind a migration.
 */
export const keywebV1Profile = defineV1CompatibilityProfile({
  appId: "keyweb",
  filename: "keyweb-vault-v1.json",
  aad: "keyweb-vault-envelope-v1",
  hkdfInfo: "keyweb-vault-content-key-v1",
  compression: "gzip-if-smaller",
  passkey: {
    rpName: "Keyweb",
    userName: "vault",
    userDisplayName: "Keyweb vault",
    algorithm: -7,
    residentKey: "required",
    userVerification: "required",
    timeoutMs: 60_000,
  },
});

/**
 * The WebAuthn relying party. A passkey is bound to this exactly, so it must be
 * the registrable domain the app is served from — `keyneom.github.io` in
 * production, `localhost` in development. Getting it wrong does not fail
 * loudly; it simply never finds the existing passkey.
 */
export function keywebRpId(hostname: string = location.hostname): string {
  return hostname;
}
