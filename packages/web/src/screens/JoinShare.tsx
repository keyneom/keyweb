import { useState } from "react";
import { AlertIcon, CheckIcon, CopyIcon, KeyIcon, ShieldIcon } from "../ui/icons";
import { MissingGrant } from "../vault/sharing/picker";
import type { KeywebJoinLink } from "../vault/sharing/links";
import type { SharingApi } from "../vault/useVault";

/**
 * Somebody has shared a keyring with you.
 *
 * Three steps, and the middle one is the only one that asks anything of the
 * person: Google's file chooser, because a per-file grant is something only
 * they can give. Everything before it — confirming who you are, checking the
 * invitation's signature — happens on the one tap that starts it, while the
 * tap is still on screen. Everything after it happens without another word.
 *
 * That ordering is the whole design. A passkey prompt that arrives *after* the
 * trip through Google's chooser arrives when attention has moved on, or after
 * the browser has thrown the page away and reloaded it — and there is no way
 * to tell a person dismissing a prompt from a browser refusing one.
 */
export function JoinShare({
  invite,
  sharing,
  onDone,
  onToast,
}: {
  invite: KeywebJoinLink;
  sharing: SharingApi;
  onDone: () => void;
  onToast: (message: string) => void;
}) {
  const [stage, setStage] = useState<"preview" | "working" | "reply">("preview");
  const [error, setError] = useState<string | null>(null);
  const [missing, setMissing] = useState(false);
  const [link, setLink] = useState<string | null>(null);

  const role = invite.files[0]?.role ?? invite.invitation.requestedGrants[0]?.role ?? "viewer";
  const label = invite.label ?? "a keyring";

  async function join() {
    setStage("working");
    setError(null);
    setMissing(false);
    try {
      const result = await sharing.joinFromLink({
        invitation: invite.invitation,
        files: invite.files,
        label: invite.label,
      });
      setLink(result.link);
      setStage("reply");
    } catch (cause) {
      if (cause instanceof MissingGrant) setMissing(true);
      setError(
        cause instanceof Error ? cause.message : "Keyweb couldn't finish joining that keyring.",
      );
      setStage("preview");
    }
  }

  if (stage === "reply" && link) {
    return (
      <section className="unlock">
        <span className="unlock-mark" aria-hidden="true">
          <CheckIcon />
        </span>
        <h1 className="unlock-title">One last thing</h1>
        <p className="unlock-sub">
          Send this reply back to {invite.ownerEmail ?? "the person who invited you"}. Until they
          open it, {label} won't appear here.
        </p>

        <button
          type="button"
          className="btn pri big"
          onClick={async () => {
            await navigator.clipboard.writeText(link);
            onToast("The reply copied. Paste it into a message back to them.");
          }}
        >
          <CopyIcon />
          Copy the reply
        </button>

        <p className="status" data-tone="calm">
          <ShieldIcon />
          <span>
            <b>Nothing has been handed over yet.</b>
            <em>
              This reply carries only your public key — the half that locks things, never the half
              that opens them.
            </em>
          </span>
        </p>

        <button type="button" className="btn sec" onClick={onDone}>
          I've sent it
        </button>
      </section>
    );
  }

  return (
    <section className="unlock">
      <span className="unlock-mark" aria-hidden="true">
        <KeyIcon />
      </span>

      <h1 className="unlock-title">
        {invite.ownerEmail ? `${invite.ownerEmail} shared` : "Someone shared"} {label} with you
      </h1>
      <p className="unlock-sub">
        {role === "writer"
          ? "You'll be able to see the passwords in it, and add and change them."
          : "You'll be able to see the passwords in it. You won't be able to change them."}
      </p>

      {error && (
        <p className="status" data-tone="attn">
          <AlertIcon />
          <span>
            <b>{error}</b>
            {missing && <em>Nothing was lost. Open the chooser again and pick it.</em>}
          </span>
        </p>
      )}

      <button
        type="button"
        className="btn pri big"
        disabled={stage === "working"}
        onClick={() => void join()}
      >
        {stage === "working" ? "Confirming it's you…" : missing ? "Try again" : "Continue"}
      </button>

      <p className="status" data-tone="calm">
        <ShieldIcon />
        <span>
          <b>You'll be asked for two things.</b>
          <em>
            Your passkey, to prove it's you — then Google's file chooser, to pick {label}. Google
            only lets you grant one file at a time, and only you can do it.
          </em>
        </span>
      </p>

      <button type="button" className="btn sec" onClick={onDone}>
        Not now
      </button>
    </section>
  );
}
