import { AlertIcon, KeyIcon, ShieldIcon } from "../ui/icons";
import type { VaultPhase } from "../vault/useVault";

/**
 * The unlock gate.
 *
 * Deliberately does not fire the passkey prompt on page load. A system dialog
 * appearing before anyone has asked for one reads as something going wrong;
 * the button says what will happen, then it happens.
 */
export function Unlock({
  phase,
  firstRun,
  error,
  onUnlock,
}: {
  phase: VaultPhase;
  firstRun: boolean;
  error: string | null;
  onUnlock: () => void;
}) {
  if (phase === "unsupported") {
    return (
      <section className="unlock">
        <p className="status" data-tone="risk">
          <AlertIcon />
          <span>
            <b>Keyweb can't open safely in this browser.</b>
            <em>
              It needs a secure connection and passkey support. Try Chrome, Safari, Edge or
              Firefox, and check the address starts with https.
            </em>
          </span>
        </p>
      </section>
    );
  }

  const busy = phase === "unlocking";

  return (
    <section className="unlock">
      <span className="unlock-mark" aria-hidden="true">
        <KeyIcon />
      </span>

      <h1 className="unlock-title">{firstRun ? "Set up Keyweb" : "Welcome back"}</h1>
      <p className="unlock-sub">
        {firstRun
          ? "Keyweb locks your passwords with the same face, fingerprint or PIN you use to unlock this device. Nothing to remember."
          : "Unlock your passwords with your face, fingerprint or PIN."}
      </p>

      {error && (
        <p className="status" data-tone="attn">
          <AlertIcon />
          <span>
            <b>{error}</b>
            <em>You can try again below.</em>
          </span>
        </p>
      )}

      <button type="button" className="btn pri big" onClick={onUnlock} disabled={busy}>
        {busy ? "Waiting for you…" : firstRun ? "Set up Keyweb" : "Unlock"}
      </button>

      {firstRun && (
        <p className="status" data-tone="calm" style={{ marginTop: "1.25rem" }}>
          <ShieldIcon />
          <span>
            <b>Your passwords are scrambled on this device.</b>
            <em>
              Without your face, fingerprint or PIN, what's stored here is unreadable — even to
              another website on this computer.
            </em>
          </span>
        </p>
      )}
    </section>
  );
}
