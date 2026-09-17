/**
 * Choosing how a list is ordered.
 *
 * One control, not a field and a direction arrow. "Descending" means nothing
 * until you also know what it applies to, so each ordering names both of its
 * ends — "Name (A–Z)" and "Name (Z–A)" — and what will happen is legible
 * before it happens.
 *
 * A native `<select>`, so it is operable by keyboard, announced correctly, and
 * opens as the platform's own picker on a phone rather than as a menu somebody
 * has to learn.
 */
export function SortPicker<T extends string>({
  label,
  value,
  options,
  onChange,
}: {
  label: string;
  value: T;
  options: readonly { value: T; label: string }[];
  onChange: (value: T) => void;
}) {
  return (
    <label className="field sortpicker">
      <span>{label}</span>
      <div className="box">
        <select value={value} onChange={(e) => onChange(e.target.value as T)} aria-label={label}>
          {options.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      </div>
    </label>
  );
}
