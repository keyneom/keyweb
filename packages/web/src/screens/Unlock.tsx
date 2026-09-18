import { useState } from "react";
import { AlertIcon, KeyIcon, ShieldIcon } from "../ui/icons";
import type { AccountContents } from "../vault/drive";
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
  contents,
}: {
  phase: VaultPhase;
  firstRun: boolean;
  error: string | null;
  backupConfigured: boolean;
  onUnlock: () => void;
  onRestore: () => void;
  onRestoreWithCode: (code: string) => void;
  /** What the account holds, once something has looked. Null until then. */
  contents: AccountContents | null;
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

      {/*
        What is in the account, whenever something went wrong reaching it.

        The complaint this answers is a screen that comes up empty and says it
        is synced, which is indistinguishable from an account with nothing in
        it. Those are opposite situations, and nobody should have to tell them
        apart by feel. None of it needs a key: the timestamp comes from the
        envelope header and the names are Drive file names their owner chose.
      */}
      {error && contents && (
        <div className="status" data-tone="calm">
          <ShieldIcon />
          <span>
            <b>Your passwords are still there.</b>
            <em>
              {contents.backup
                ? `This Google account has a Keyweb backup, last changed ${whenChanged(
                    contents.backup.updatedAt,
                  )}. ${
                    contents.backup.opensWithCode
                      ? "Your recovery code opens it."
                      : "It has no recovery copy, so only the device that made it can open it."
                  }`
                : "This Google account has no Keyweb backup in it yet."}
              {contents.sharedKeyrings.length > 0 &&
                ` Shared with you: ${contents.sharedKeyrings.join(", ")}.`}
            </em>
          </span>
        </div>
      )}

      <button type="button" className="btn pri big" onClick={onUnlock} disabled={busy}>
        {busy ? "Waiting for you…" : firstRun ? "Set up Keyweb" : "Unlock"}
      </button>

      {/*
        Not `firstRun` any more, and that was the bug.

        The recovery code was reachable on the very first screen and nowhere
        else — so the one person who needs it, whose browser already has a
        vault it cannot reconcile with the backup, could never get to it. The
        app told them to enter their code beside no field to enter it in.

        Somebody who has set this browser up is exactly who needs the way back
        in, not somebody who has not.
      */}
      {backupConfigured && (
        <>
          <p className="unlock-or">or</p>
          {firstRun && (
            <>
              <button type="button" className="btn sec big" onClick={onRestore} disabled={busy}>
                I already use Keyweb — restore my passwords
              </button>
              <p className="unlock-note">
                Sign in with the same Google account and unlock with the same face, fingerprint
                or PIN you used before.
              </p>
            </>
          )}

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
                  Capital letters, lower case and missing dashes are all fine. There is no letter{" "}
                  <b>O</b>, <b>I</b>, <b>L</b> or <b>U</b> in a recovery code, so anything that
                  looks like one is a <b>0</b> or a <b>1</b> — and Keyweb accepts it either way.
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
            /*
              Not "I can't use that Google account". Somebody whose vault was
              made on a phone *can* use the account — the phone simply cannot
              create a key for a browser, so the code is their ordinary first
              visit rather than a last resort. Labelling it as a failure sent
              them looking for a problem with their Google sign-in.
            */
            <button type="button" className="linkish" onClick={() => setShowCode(true)}>
              {firstRun
                ? "Use my recovery code instead — or if my vault was made on a phone"
                : "My passwords are missing — open the backup with my recovery code"}
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

/**
 * When the backup last changed, in words.
 *
 * Somebody checking whether their passwords survived is reading this to decide
 * whether the date looks like the last time they used the app. "3 hours ago"
 * answers that; an ISO timestamp makes them do arithmetic under stress.
 */
function whenChanged(at: string | null): string {
  if (!at) return "at some point";
  const then = Date.parse(at);
  if (Number.isNaN(then)) return "at some point";
  const minutes = Math.floor((Date.now() - then) / 60_000);
  if (minutes < 2) return "just now";
  if (minutes < 60) return `${minutes} minutes ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} hour${hours === 1 ? "" : "s"} ago`;
  const days = Math.floor(hours / 24);
  return days === 1 ? "yesterday" : `${days} days ago`;
}
