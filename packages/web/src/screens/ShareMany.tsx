import { useState } from "react";
import { SHARE_ROLES } from "../vault/sharing/operations";
import type { ShareRole } from "../vault/sharing";
import { AlertIcon } from "../ui/icons";

/**
 * Inviting one person to several keyrings, on one link.
 *
 * Deliberately not the sharing screen for a keyring. That screen is about one
 * keyring and everything known about it — who is on it, what is outstanding,
 * handing it over — and none of that has an answer for a group. This asks the
 * one question a group *does* have an answer to, who and what may they do, and
 * hands back the one thing there is to send.
 *
 * One role covers every keyring in the invitation. Different roles for
 * different keyrings is a second question, and sending two links is already
 * the way to ask it.
 */
export function ShareMany({
  names,
  onInvite,
  onCopy,
  onClose,
}: {
  names: string[];
  onInvite: (email: string, role: ShareRole) => Promise<string>;
  onCopy: (link: string) => void;
  onClose: () => void;
}) {
  const [email, setEmail] = useState("");
  const [role, setRole] = useState<ShareRole>("viewer");
  const [busy, setBusy] = useState(false);
  const [link, setLink] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  return (
    <section className="sheet">
      <h2>{names.length === 1 ? `Share ${names[0]}` : `Share ${names.length} keyrings`}</h2>
      <p className="hint">
        <b>{names.join(", ")}</b>
      </p>
      <p className="hint">
        They see every password in {names.length === 1 ? "it" : "these"}, and any you add later.
        One link covers all of them.
      </p>

      {error && (
        <p className="status" data-tone="risk">
          <AlertIcon />
          <span>
            <b>{error}</b>
          </span>
        </p>
      )}

      {link === null ? (
        <>
          <label className="field">
            <span>Share with</span>
            <div className="box">
              <input
                type="email"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                placeholder="their.name@gmail.com"
                autoComplete="off"
              />
            </div>
            <span className="hint">
              It has to be the Google account they use, because that is how Google lets them at
              the files.
            </span>
          </label>

          <div className="list" style={{ marginBottom: "0.75rem" }}>
            {SHARE_ROLES.map((choice) => (
              <button
                key={choice.value}
                type="button"
                className="row"
                aria-pressed={role === choice.value}
                onClick={() => setRole(choice.value)}
              >
                <span className="avatar" aria-hidden="true">
                  {role === choice.value ? "●" : "○"}
                </span>
                <span className="rowtext">
                  <b>{choice.label}</b>
                  <span>{choice.detail}</span>
                </span>
              </button>
            ))}
          </div>

          <button
            type="button"
            className="btn pri big"
            disabled={email.trim().length === 0 || busy}
            onClick={async () => {
              setBusy(true);
              setError(null);
              try {
                setLink(await onInvite(email.trim(), role));
              } catch (cause) {
                setError(
                  cause instanceof Error ? cause.message : "That invitation couldn't be made.",
                );
              } finally {
                setBusy(false);
              }
            }}
          >
            {busy ? "Getting it ready…" : "Make a link to send them"}
          </button>
        </>
      ) : (
        <>
          <p className="hint">
            They open it, choose each keyring in Google's file chooser, and send you a reply link
            back. Google grants one file at a time, so there will be one chooser per keyring — but
            only one reply to open.
          </p>
          <button type="button" className="btn pri big" onClick={() => onCopy(link)}>
            Copy the link
          </button>
        </>
      )}

      <button type="button" className="btn sec big" onClick={onClose}>
        {link === null ? "Cancel" : "Done"}
      </button>
    </section>
  );
}
