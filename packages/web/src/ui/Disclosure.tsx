import { useState, type ReactNode } from "react";

/**
 * A section that stays out of the way until somebody asks for it.
 *
 * The app has grown a lot of capability — files, one-time codes, fields you
 * name yourself, earlier values, sharing — and all of it arrived on the same
 * two screens. The answer is deliberately *not* an "advanced mode" switch in
 * settings: a mode is invisible state, so somebody who flipped it last month
 * meets a different app than the one they learned, somebody who never finds it
 * never gets the feature, and every screen has to be designed twice.
 *
 * Disclosure in place costs one click, is discoverable exactly where it is
 * relevant, and leaves the common path as short as it was. The label says what
 * is inside in the words the person would use, never "Advanced" — that is a
 * word that tells somebody the thing they are looking for is not for them.
 *
 * `<details>` rather than a div and some state, so it opens without JavaScript,
 * is announced as a disclosure by a screen reader, and is findable by the
 * browser's own find-in-page.
 */
export function Disclosure({
  label,
  hint,
  /**
   * Open for an item that already has something inside. Hiding a field
   * somebody can see today, on the grounds that it is advanced, is how these
   * fields went missing in the first place.
   */
  initiallyOpen = false,
  children,
}: {
  label: string;
  hint?: string;
  initiallyOpen?: boolean;
  children: ReactNode;
}) {
  const [open, setOpen] = useState(initiallyOpen);
  return (
    <details className="disclosure" open={open} onToggle={(e) => setOpen(e.currentTarget.open)}>
      <summary>
        <span className="disclosure-label">{label}</span>
        {hint && !open && <span className="hint">{hint}</span>}
      </summary>
      <div className="disclosure-body">{children}</div>
    </details>
  );
}
