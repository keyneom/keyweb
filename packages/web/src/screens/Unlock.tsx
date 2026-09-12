import { useState } from "react";
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
  backupConfigured,
  onUnlock,
  onRestore,
  onRestoreWithCode,
}: {
  phase: VaultPhase;
  firstRun: boolean;
  error: string | null;
  backupConfigured: boolean;
  onUnlock: () => void;
  onRestore: () => void;
  onRestoreWithCode: (code: string) => void;
}) {
  const [code, setCode] = useState("");
  const [showCode, setShowCode] = useState(false);
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

      {firstRun && backupConfigured && (
        <>
          <p className="unlock-or">or</p>
          <button type="button" className="btn sec big" onClick={onRestore} disabled={busy}>
            I already use Keyweb — restore my passwords
          </button>
          <p className="unlock-note">
            Sign in with the same Google account and unlock with the same face, fingerprint or
            PIN you used before.
          </p>

          {showCode ? (
            <div className="code-entry">
              <label className="field">
                <span>Your recovery code</span>
                <div className="box">
                  <input
                    className="mono"
                    value={code}
                    onChange={(event) => setCode(event.target.value)}
                    placeholder="H7K2-9MNP-4RTV-8XZ3-QWC6-JD5F-P2TM-6BKX"
                    autoComplete="off"
                    spellCheck={false}
                  />
                </div>
                <span className="hint">
                  Capital letters, lower case and missing dashes are all fine.
                </span>
              </label>
              <button
                type="button"
                className="btn sec big"
                disabled={busy || code.trim().length === 0}
                onClick={() => onRestoreWithCode(code)}
              >
                Open my backup with this code
              </button>
            </div>
          ) : (
            <button type="button" className="linkish" onClick={() => setShowCode(true)}>
              I can't use that Google account — I have a recovery code
            </button>
          )}
        </>
      )}

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
