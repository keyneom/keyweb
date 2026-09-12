import { useState } from "react";
import { AlertIcon, ShieldIcon } from "../ui/icons";

/**
 * The recovery sheet.
 *
 * Shown once, immediately after setup, because this is the only moment the code
 * exists in a form anyone can read. It is never shown again: keeping it
 * retrievable would defeat the purpose of putting it on paper.
 *
 * Deliberately blunt about the consequence. "No one at Keyweb can unlock this"
 * is not a disclaimer, it is the product: there is no server holding a key, so
 * there is genuinely nobody to phone. Softening that would be the one lie in
 * the app.
 */
export function RecoverySheet({
  code,
  onDone,
}: {
  code: string;
  onDone: () => void;
}) {
  const [confirmed, setConfirmed] = useState(false);

  return (
    <section className="sheet-screen">
      <h1 className="screen-title">Your recovery code</h1>
      <p className="screen-sub">
        This is the only way back to your passwords if you lose your phone <em>and</em> your
        Google account. Print it or write it down now — you won't be shown it again.
      </p>

      <article className="sheet" aria-label="Keyweb recovery sheet">
        <h2>Keyweb Recovery Sheet</h2>
        <p className="sheet-sub">Created {new Date().toLocaleDateString()}</p>
        <p className="sheet-code">{code}</p>
        <ol>
          <li>Keep this on paper. Don't photograph it or save it on a computer.</li>
          <li>Store it where you keep your passport or birth certificate.</li>
          <li>
            Anyone holding this sheet can read all of your passwords. Treat it like a spare house
            key.
          </li>
        </ol>
        <p className="sheet-stamp">
          No one at Keyweb can read your passwords or reset them for you. There is no "forgot
          password" for this code. If it is lost and your devices are gone, the passwords are gone.
        </p>
      </article>

      <div className="stack no-print">
        <button type="button" className="btn sec big" onClick={() => window.print()}>
          Print this sheet
        </button>

        <label className="confirm">
          <input
            type="checkbox"
            checked={confirmed}
            onChange={(event) => setConfirmed(event.target.checked)}
          />
          <span>I've printed it or written it down somewhere safe.</span>
        </label>

        <button type="button" className="btn pri big" disabled={!confirmed} onClick={onDone}>
          Continue to my passwords
        </button>
      </div>

      <p className="status no-print" data-tone="attn">
        <AlertIcon />
        <span>
          <b>You won't see this code again.</b>
          <em>Keyweb keeps it scrambled so it can keep your backup current, but never shows it.</em>
        </span>
      </p>

      <p className="status no-print" data-tone="calm">
        <ShieldIcon />
        <span>
          <b>You probably won't need it.</b>
          <em>
            On a new phone, signing in with the same Google account and your face or fingerprint is
            usually enough. This is the backstop for when that isn't possible.
          </em>
        </span>
      </p>
    </section>
  );
}
