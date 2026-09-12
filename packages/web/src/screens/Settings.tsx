import { BackIcon } from "../ui/icons";
import type { Appearance, TextSize } from "../vault/useDisplaySettings";

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
}: {
  textSize: TextSize;
  appearance: Appearance;
  onTextSize: (value: TextSize) => void;
  onAppearance: (value: Appearance) => void;
  onBack: () => void;
  onImport: () => void;
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
