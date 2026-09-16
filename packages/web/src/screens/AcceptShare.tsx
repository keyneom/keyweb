import { useCallback, useEffect, useRef, useState } from "react";
import type { SharingPublicKeyResponseV1 } from "@keyneom/sync-kit/sharing";
import { AlertIcon, CheckIcon, ShieldIcon } from "../ui/icons";
import type { SharingApi } from "../vault/useVault";

/**
 * They replied. This is the step that actually lets them in.
 *
 * The wrap waits behind a button. The two links travel over ordinary chat,
 * and the one thing an attacker on that channel can do is substitute their
 * own key for the recipient's. Six characters read out loud is the whole
 * defence, so they have to be on screen *before* the content key is wrapped
 * to whoever presented it — not on a confirmation that already happened.
 */
export function AcceptShare({
  response,
  sharing,
  onDone,
}: {
  response: SharingPublicKeyResponseV1;
  sharing: SharingApi;
  onDone: () => void;
}) {
  const [error, setError] = useState<string | null>(null);
  const [preview, setPreview] = useState<{
    label: string;
    email: string;
    fingerprint: string;
  } | null>(null);
  const [done, setDone] = useState<{ label: string; email: string; fingerprint: string } | null>(
    null,
  );
  const started = useRef(false);

  const load = useCallback(async () => {
    setError(null);
    try {
      setPreview(await sharing.previewResponse(response));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Keyweb couldn't finish that.");
    }
  }, [response, sharing]);

  const accept = useCallback(async () => {
    setError(null);
    try {
      setDone(await sharing.acceptResponse(response));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Keyweb couldn't finish that.");
    }
  }, [response, sharing]);

  // Preview once. A failure puts an error on screen and must not immediately
  // try again behind it.
  useEffect(() => {
    if (started.current) return;
    started.current = true;
    void load();
  }, [load]);

  if (done) {
    return (
      <section className="unlock">
        <span className="unlock-mark" aria-hidden="true">
          <CheckIcon />
        </span>
        <h1 className="unlock-title">
          {done.email} can see {done.label} now
        </h1>
        <p className="unlock-sub">
          It will show up on their device the next time Keyweb saves. There is nothing else to
          send.
        </p>

        <button type="button" className="btn pri big" onClick={onDone}>
          Done
        </button>
      </section>
    );
  }

  if (preview && !error) {
    return (
      <section className="unlock">
        <span className="unlock-mark" aria-hidden="true">
          <ShieldIcon />
        </span>
        <h1 className="unlock-title">
          Let {preview.email} into {preview.label}?
        </h1>
        <p className="unlock-sub">
          Ask them to read their key out. If it doesn't match, don't let them in — someone else
          may have got hold of the link.
        </p>

        <p className="status" data-tone="calm">
          <ShieldIcon />
          <span>
            <b>Their key is {preview.fingerprint}.</b>
            <em>Keyweb shows them the same ones. This is the check, not a formality after it.</em>
          </span>
        </p>

        <button type="button" className="btn pri big" onClick={() => void accept()}>
          Their key matches — let them in
        </button>
        <button type="button" className="btn sec" onClick={onDone}>
          Not this person
        </button>
      </section>
    );
  }

  return (
    <section className="unlock">
      <span className="unlock-mark" aria-hidden="true">
        <ShieldIcon />
      </span>
      <h1 className="unlock-title">{error ? "That didn't work" : "Checking the reply…"}</h1>

      {error ? (
        <>
          <p className="status" data-tone="attn">
            <AlertIcon />
            <span>
              <b>{error}</b>
            </span>
          </p>
          <button
            type="button"
            className="btn pri big"
            onClick={() => {
              void (preview ? accept() : load());
            }}
          >
            Try again
          </button>
          <button type="button" className="btn sec" onClick={onDone}>
            Not now
          </button>
        </>
      ) : (
        <p className="unlock-sub">Confirming the invitation this reply belongs to.</p>
      )}
    </section>
  );
}
