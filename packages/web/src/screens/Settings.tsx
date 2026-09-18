import { useState } from "react";
import { BackIcon } from "../ui/icons";
import type { Appearance, TextSize } from "../vault/useDisplaySettings";
import type { SharingApi } from "../vault/useVault";

/**
 * The six characters that name you to the people you share with.
 *
 * Behind a button rather than shown on arrival, because reading it needs the
 * passkey, and a settings screen that demands a fingerprint prompt the moment
 * it opens teaches people to dismiss prompts without reading them.
 *
 * It is worth having at all because the share links travel over ordinary chat.
 * The one thing an attacker on that channel can do is substitute their own key
 * for someone's, and two people reading six characters to each other is what
 * catches it.
 */
function SharingKey({ sharing }: { sharing: SharingApi }) {
  const [value, setValue] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  return (
    <fieldset style={{ border: 0, padding: 0, margin: "0 0 1.75rem" }}>
      <legend style={{ fontWeight: 650, fontSize: "0.95em", padding: 0, marginBottom: "0.15rem" }}>
        Your sharing key
      </legend>
      <p style={{ color: "var(--muted)", fontSize: "0.86em", margin: "0 0 0.7rem" }}>
        When somebody shares a keyring with you, this is how their Keyweb knows it is really you.
        It is the same on every device you sign in to with this Google account.
      </p>
      {value ? (
        <p className="status" data-tone="calm">
          <span>
            <b className="mono">{value}</b>
            <em>
              Read these out to the person you are sharing with. If what they see doesn't match,
              the link was tampered with on the way.
            </em>
          </span>
        </p>
      ) : (
        <button
          type="button"
          className="btn sec big"
          disabled={busy}
          onClick={async () => {
            setBusy(true);
            setError(null);
            try {
              setValue(await sharing.myFingerprint());
            } catch (cause) {
              setError(
                cause instanceof Error ? cause.message : "Keyweb couldn't read your sharing key.",
              );
            } finally {
              setBusy(false);
            }
          }}
        >
          {busy ? "Confirming it's you…" : "Show my sharing key"}
        </button>
      )}
      {error && (
        <p className="status" data-tone="attn">
          <span>
            <b>{error}</b>
          </span>
        </p>
      )}
    </fieldset>
  );
}

function Choice<T extends string>({
  label,
  hint,
  value,
  options,
  onChange,
}: {
  label: string;
  hint: string;
  value: T;
  options: { value: T; label: string }[];
  onChange: (next: T) => void;
}) {
  return (
    <fieldset style={{ border: 0, padding: 0, margin: "0 0 1.75rem" }}>
      <legend style={{ fontWeight: 650, fontSize: "0.95em", padding: 0, marginBottom: "0.15rem" }}>
        {label}
      </legend>
      <p style={{ color: "var(--muted)", fontSize: "0.86em", margin: "0 0 0.7rem" }}>{hint}</p>
      <div className="stack">
        {options.map((option) => (
          <button
            key={option.value}
            type="button"
            className={value === option.value ? "btn pri big" : "btn sec big"}
            aria-pressed={value === option.value}
            onClick={() => onChange(option.value)}
          >
            {option.label}
          </button>
        ))}
      </div>
    </fieldset>
  );
}

export function Settings({
  textSize,
  appearance,
  onTextSize,
  onAppearance,
  onBack,
  onImport,
  onLock,
  sharing,
}: {
  textSize: TextSize;
  appearance: Appearance;
  onTextSize: (value: TextSize) => void;
  onAppearance: (value: Appearance) => void;
  onBack: () => void;
  onImport: () => void;
  onLock: () => void;
  /** Null when this build has no Google account and so cannot share at all. */
  sharing: SharingApi | null;
}) {
  return (
    <>
      <header className="topbar">
        <button type="button" className="iconbtn" onClick={onBack}>
          <BackIcon />
          Back
        </button>
      </header>

      <h1 className="screen-title">Settings</h1>
      <p className="screen-sub">These change straight away. You can come back and change them again.</p>

      {/*
        First, because it is the thing somebody comes here in a hurry to do —
        stepping away from a shared computer. Locking existed from the
        beginning and was reachable from nowhere: the only way to lock was to
        close the tab, which is not something a person does deliberately while
        someone is waiting to use the machine.
      */}
      <fieldset style={{ border: 0, padding: 0, margin: "0 0 1.75rem" }}>
        <legend style={{ fontWeight: 650, fontSize: "0.95em", padding: 0, marginBottom: "0.15rem" }}>
          Lock Keyweb
        </legend>
        <p style={{ color: "var(--muted)", fontSize: "0.86em", margin: "0 0 0.7rem" }}>
          Closes your passwords straight away. You'll need your face, fingerprint or PIN to open
          them again.
        </p>
        <button type="button" className="btn sec big" onClick={onLock}>
          Lock now
        </button>
      </fieldset>

      <Choice
        label="Text size"
        hint="Makes everything in Keyweb bigger, including the buttons."
        value={textSize}
        options={[
          { value: "normal", label: "Normal text" },
          { value: "large", label: "Larger text" },
        ]}
        onChange={onTextSize}
      />

      <fieldset style={{ border: 0, padding: 0, margin: "0 0 1.75rem" }}>
        <legend style={{ fontWeight: 650, fontSize: "0.95em", padding: 0, marginBottom: "0.15rem" }}>
          Coming from another password app
        </legend>
        <p style={{ color: "var(--muted)", fontSize: "0.86em", margin: "0 0 0.7rem" }}>
          Bring in a KeePass or KeeWeb file, keeping your folders and tags.
        </p>
        <button type="button" className="btn sec big" onClick={onImport}>
          Import from KeePass or KeeWeb
        </button>
      </fieldset>

      {sharing && <SharingKey sharing={sharing} />}

      <Choice
        label="Light or dark"
        hint="Match device follows whatever your computer or phone is set to."
        value={appearance}
        options={[
          { value: "device", label: "Match my device" },
          { value: "light", label: "Always light" },
          { value: "dark", label: "Always dark" },
        ]}
        onChange={onAppearance}
      />
    </>
  );
}
