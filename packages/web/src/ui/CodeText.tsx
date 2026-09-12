/**
 * Characters that must not be mistaken for one another.
 *
 * Someone copying a recovery code onto paper has to decide, for every glyph,
 * whether they are looking at a zero or a letter O. Getting it wrong is not a
 * typo they will notice — it surfaces months later, on the one day the code
 * matters, as "that code isn't right".
 *
 * The alphabet already rules out the worst pairs: Crockford base32 omits I, L,
 * O and U entirely. But that guarantee is useless to the reader, who cannot see
 * it. So the code is drawn with digits and letters visibly distinct, and the
 * guarantee is stated in words underneath. Both halves matter: the colour says
 * "these are different kinds of thing", the sentence says which.
 */
export function CodeText({ value, className }: { value: string; className?: string }) {
  return (
    <span className={className}>
      {[...value].map((character, index) => (
        <span
          // Positional keys are correct here: this is a fixed string being
          // drawn, not a list that reorders.
          key={index}
          className={
            /[0-9]/.test(character)
              ? "glyph digit"
              : /[A-Z]/.test(character)
                ? "glyph letter upper"
                : /[a-z]/.test(character)
                  ? "glyph letter lower"
                  : "glyph"
          }
        >
          {character}
        </span>
      ))}
    </span>
  );
}

/**
 * The reading key, shown wherever a code is.
 *
 * Written as a fact about this code rather than as advice, because "be careful"
 * asks the reader to do the work and this sentence does it for them.
 *
 * The all-capitals fact earns its place: without it someone writing the code in
 * their own hand may mix cases, and then cannot tell later whether their own `c`
 * meant `c` or `C`. It never meant `c` — there are no lower-case letters in a
 * recovery code at all.
 */
export function CodeLegend() {
  return (
    <p className="code-legend">
      <span>
        <b className="glyph digit">123</b> numbers
      </span>
      <span>
        <b className="glyph letter upper">ABC</b> letters
      </span>
      <span className="code-legend-note">
        Every letter is a <b>capital</b>, and there is no letter <b>O</b>, <b>I</b>, <b>L</b> or{" "}
        <b>U</b> — so <b className="glyph digit">0</b> is always zero and{" "}
        <b className="glyph digit">1</b> is always one.
      </span>
    </p>
  );
}
