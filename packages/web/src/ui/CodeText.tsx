/**
 * Characters that must not be mistaken for one another.
 *
 * Someone copying a code or a password onto paper has to decide, for every
 * glyph, what they are looking at. Zero against letter O is the famous pair,
 * but the worse ones are the case twins — `c` and `C`, `s` and `S`, `v` and `V`
 * — which differ only in size, so an isolated glyph carries no cue at all.
 * Getting one wrong is not a typo anyone notices; it surfaces later as a code
 * that simply does not work, with nothing to say which character was wrong.
 *
 * The fix is display rather than alphabet. Dropping the ambiguous characters
 * would also work, but it spends real entropy to solve a rendering problem. So
 * every character is drawn in the colour of its category, and a legend says
 * which is which.
 *
 * ## Why these colours
 *
 * Every pair stays at least 24 ΔE apart under simulated protanopia,
 * deuteranopia *and* tritanopia, and each clears 4.5:1 contrast against its
 * background. Hue alone does not survive colour blindness — under red-green
 * deficiency several hues collapse toward one axis — so the categories are
 * separated by lightness as well, and case carries extra weight on top. Three
 * independent cues, any one of which is enough on its own.
 */

export type GlyphCategory = "upper" | "lower" | "digit" | "symbol";

export function categoryOf(character: string): GlyphCategory {
  if (/[0-9]/.test(character)) return "digit";
  if (/[A-Z]/.test(character)) return "upper";
  if (/[a-z]/.test(character)) return "lower";
  return "symbol";
}

export function CodeText({ value, className }: { value: string; className?: string }) {
  return (
    <span className={className}>
      {[...value].map((character, index) => (
        <span
          // Positional keys are correct here: this is a fixed string being
          // drawn, not a list that reorders.
          key={index}
          className={`glyph ${categoryOf(character)}`}
        >
          {character}
        </span>
      ))}
    </span>
  );
}

/**
 * The reading key.
 *
 * Written as facts about the characters rather than as advice, because "be
 * careful" asks the reader to do the work and these sentences do it for them.
 *
 * The recovery variant can make a stronger promise than the password one: its
 * alphabet genuinely has no lower-case letter and no O, I, L or U, so those
 * ambiguities do not merely look resolved, they do not exist. A password made
 * of arbitrary characters gets the honest version instead.
 */
export function CodeLegend({ recovery = false }: { recovery?: boolean }) {
  return (
    <div className="code-legend">
      <p className="code-legend-row">
        <span>
          <b className="glyph upper">AB</b>
          <b className="glyph lower">cd</b> letters
        </span>
        <span>
          <b className="glyph digit">123</b> numbers
        </span>
        <span>
          <b className="glyph symbol">-@#</b> symbols
        </span>
      </p>
      <p className="code-legend-note">
        {recovery ? (
          <>
            Every letter is a <b>capital</b>, and there is no letter <b>O</b>, <b>I</b>, <b>L</b> or{" "}
            <b>U</b> — so <b className="glyph digit">0</b> is always zero and{" "}
            <b className="glyph digit">1</b> is always one.
          </>
        ) : (
          <>
            <b className="glyph upper">CAPITALS</b> are darker and bolder,{" "}
            <b className="glyph lower">small letters</b> lighter. They are not interchangeable —
            copy them exactly as they appear.
          </>
        )}
      </p>
    </div>
  );
}
