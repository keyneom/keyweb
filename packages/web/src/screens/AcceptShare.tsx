import { useCallback, useEffect, useRef, useState } from "react";
import type { SharingPublicKeyResponseV1 } from "@keyneom/sync-kit/sharing";
import { AlertIcon, CheckIcon, ShieldIcon } from "../ui/icons";
import type { SharingApi } from "../vault/useVault";

/**
 * They replied. This is the step that actually lets them in.
 *
 * Runs on arrival rather than behind a button, because by this point the owner
 * has already decided — they typed the address, they chose what the person may
 * do, and they sent the link. Asking again here would be asking the same
 * question twice.
 *
 * What it does show, and does not hide, is the key fingerprint. The two links
 * travel over ordinary chat, and the one thing an attacker on that channel can
 * do is substitute their own key for the recipient's. Six characters read out
 * loud is the whole defence, so it is on the screen at the moment it can still
 * be acted on rather than in a settings page nobody opens.
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
  const [done, setDone] = useState<{ label: string; email: string; fingerprint: string } | null>(
    null,
  );
  const started = useRef(false);

  const run = useCallback(async () => {
    setError(null);
    try {
      setDone(await sharing.acceptResponse(response));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Keyweb couldn't finish that.");
    }
  }, [response, sharing]);

  // Once, guarded by a ref rather than by state: a failure puts an error on
  // screen and must not immediately try again behind it.
  useEffect(() => {
    if (started.current) return;
    started.current = true;
    void run();
  }, [run]);

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

        <p className="status" data-tone="calm">
          <ShieldIcon />
          <span>
            <b>Their key is {done.fingerprint}.</b>
            <em>
              If you want to be certain the reply came from them and not from someone who got hold
              of the link, ask them to read those six characters out. Keyweb shows them the same
              ones.
            </em>
          </span>
        </p>

        <button type="button" className="btn pri big" onClick={onDone}>
          Done
        </button>
      </section>
    );
  }

  return (
    <section className="unlock">
      <span className="unlock-mark" aria-hidden="true">
        <ShieldIcon />
      </span>
      <h1 className="unlock-title">{error ? "That didn't work" : "Letting them in…"}</h1>

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
              void run();
            }}
          >
            Try again
          </button>
          <button type="button" className="btn sec" onClick={onDone}>
            Not now
          </button>
        </>
      ) : (
        <p className="unlock-sub">Confirming it's you, then giving them the key.</p>
      )}
    </section>
  );
}
