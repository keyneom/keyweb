import { useEffect, useState } from "react";
import { CopyIcon, EyeIcon } from "./icons";
import { groupCode, parseOtp, secondsRemaining, totpAt, type OtpConfig } from "../vault/totp";

/**
 * The rotating second-factor code for a password.
 *
 * Behind a tap rather than on screen when the page opens, matching the
 * password above it. `docs/two-factor.md` sets the rule it follows: producing
 * a code is a separate deliberate act, never something that happens alongside
 * a password in one gesture — an attacker who gets one keystroke of assent
 * should not get a whole sign-in.
 *
 * The countdown is there because a code with three seconds left will be
 * rejected by the time somebody has typed it, and being told that beforehand
 * is the difference between waiting four seconds and thinking the code is
 * wrong.
 */
export function OtpCode({
  secret,
  onCopy,
}: {
  secret: string;
  onCopy: (value: string, label: string) => void;
}) {
  const [shown, setShown] = useState(false);
  const [code, setCode] = useState("");
  const [seconds, setSeconds] = useState(0);
  const [error, setError] = useState<string | null>(null);

  const config = parse(secret);

  useEffect(() => {
    if (!shown || !config) return;
    let cancelled = false;

    const tick = async () => {
      try {
        const now = Date.now();
        const next = await totpAt(config, now);
        if (cancelled) return;
        setCode(next);
        setSeconds(secondsRemaining(config, now));
      } catch (cause) {
        if (!cancelled) {
          setError(cause instanceof Error ? cause.message : "That code couldn't be worked out.");
        }
      }
    };

    void tick();
    // Once a second, so the countdown moves and the code changes the moment
    // the window rolls over rather than up to thirty seconds late.
    const timer = setInterval(() => void tick(), 1000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [shown, config]);

  if (!config) {
    // A seed that cannot be read is still the person's data and is shown as
    // the text it is, rather than disappearing because we could not use it.
    return (
      <label className="field">
        <span>Second-factor code</span>
        <div className="box">
          <input value={secret} readOnly aria-label="Second-factor secret" />
        </div>
        <span className="hint warn">
          Keyweb can't turn this into a code. It came across from your other app exactly as it
          was, so nothing is lost.
        </span>
      </label>
    );
  }

  return (
    <label className="field">
      <span>Second-factor code</span>
      <div className="box">
        {shown && code ? (
          <span className="mono secret-value">{groupCode(code)}</span>
        ) : (
          <span className="mono secret-value" aria-label="Code, hidden">
            ••• •••
          </span>
        )}
        <button type="button" className="iconbtn" onClick={() => setShown((v) => !v)}>
          <EyeIcon />
          {shown ? "Hide" : "Show"}
        </button>
        {shown && code && (
          <button type="button" className="iconbtn" onClick={() => onCopy(code, "The code")}>
            <CopyIcon />
            Copy
          </button>
        )}
      </div>
      <span className="hint">
        {error
          ? error
          : shown
            ? seconds <= 5
              ? `About to change — wait ${seconds} second${seconds === 1 ? "" : "s"} for a fresh one.`
              : `Changes in ${seconds} seconds.`
            : "A new code every 30 seconds. Type it after your password."}
      </span>
    </label>
  );
}

/** Parsed once per secret rather than on every tick. */
function parse(secret: string): OtpConfig | null {
  try {
    return parseOtp(secret);
  } catch {
    return null;
  }
}
