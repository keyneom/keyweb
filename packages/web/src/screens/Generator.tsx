import { useMemo, useState } from "react";
import {
  ALL_SETS,
  describeStrength,
  DEFAULT_SYMBOLS,
  generatePassword,
  PRESETS,
  rulesProblem,
  type CharacterSet,
  type PasswordRules,
  type SavedRules,
} from "@keyweb/vault-core";
import { CodeLegend, CodeText } from "../ui/CodeText";
import { AlertIcon } from "../ui/icons";

/**
 * Making a password to a site's rules.
 *
 * Opened from the password field rather than buried in settings, because the
 * moment someone needs it is the moment a site has just refused what they had.
 *
 * Every control restates its effect in words, strength is a sentence rather
 * than a coloured bar, and an impossible combination says what is wrong instead
 * of quietly producing something weaker than was asked for.
 */
const SET_LABELS: Record<CharacterSet, string> = {
  lower: "small letters",
  upper: "CAPITALS",
  digits: "numbers",
  symbols: "symbols",
};

export function Generator({
  initial,
  saved,
  onUse,
  onSaveRules,
  onClose,
}: {
  initial: PasswordRules;
  saved: SavedRules[];
  onUse: (password: string, rules: PasswordRules) => void;
  onSaveRules: (name: string, rules: PasswordRules) => void;
  onClose: () => void;
}) {
  const [rules, setRules] = useState<PasswordRules>(initial);
  const [nonce, setNonce] = useState(0);
  const [name, setName] = useState("");
  const [naming, setNaming] = useState(false);

  const problem = rulesProblem(rules);
  const candidate = useMemo(() => {
    if (problem) return "";
    try {
      return generatePassword(rules);
    } catch {
      return "";
    }
    // `nonce` is the reroll trigger: same rules, new password.
  }, [rules, problem, nonce]);
  const strength = describeStrength(rules);

  const sameRules = (a: PasswordRules, b: PasswordRules) =>
    JSON.stringify({ ...a, include: [...a.include].sort(), require: [...a.require].sort() }) ===
    JSON.stringify({ ...b, include: [...b.include].sort(), require: [...b.require].sort() });

  return (
    <section className="generator">
      <h2 className="screen-title">Make a password</h2>

      {problem ? (
        <p className="status" data-tone="attn">
          <AlertIcon />
          <span>
            <b>These rules can't work.</b>
            <em>{problem}</em>
          </span>
        </p>
      ) : (
        <>
          <CodeText value={candidate} className="generator-code" />
          <CodeLegend />
          <p className="generator-strength">
            <b data-weak={strength.bits < 60 ? "true" : "false"}>{strength.label}</b>{" "}
            <span>{strength.detail}</span>
          </p>
          <button type="button" className="btn sec" onClick={() => setNonce((n) => n + 1)}>
            Make another
          </button>
        </>
      )}

      <label className="field">
        <span>Length: {rules.length}</span>
        <input
          type="range"
          min={4}
          max={64}
          value={rules.length}
          onChange={(event) =>
            setRules((current) => ({ ...current, length: Number(event.target.value) }))
          }
        />
      </label>

      <fieldset className="generator-sets">
        <legend>Must include</legend>
        <p className="hint">
          Sites often insist on a number or a symbol. Turning one on guarantees at least one
          appears.
        </p>
        {ALL_SETS.map((set) => {
          const on = rules.require.includes(set);
          return (
            <button
              key={set}
              type="button"
              className={on ? "ring on" : "ring"}
              aria-pressed={on}
              onClick={() =>
                setRules((current) => ({
                  ...current,
                  // Off means "may appear", on means "must". Dropping it
                  // entirely is the other group, so one click never silently
                  // narrows the alphabet.
                  require: on
                    ? current.require.filter((s) => s !== set)
                    : [...current.require, set],
                  include: current.include.includes(set)
                    ? current.include
                    : [...current.include, set],
                }))
              }
            >
              {on ? "✓ " : ""}
              {SET_LABELS[set]}
            </button>
          );
        })}
      </fieldset>

      <fieldset className="generator-sets">
        <legend>Allowed at all</legend>
        <p className="hint">Turning one off keeps that kind out of the password entirely.</p>
        {ALL_SETS.map((set) => {
          const on = rules.include.includes(set);
          return (
            <button
              key={set}
              type="button"
              className={on ? "ring on" : "ring"}
              aria-pressed={on}
              onClick={() =>
                setRules((current) => ({
                  ...current,
                  include: on
                    ? current.include.filter((s) => s !== set)
                    : [...current.include, set],
                  require: on ? current.require.filter((s) => s !== set) : current.require,
                }))
              }
            >
              {on ? "✓ " : ""}
              {SET_LABELS[set]}
            </button>
          );
        })}
      </fieldset>

      <label className="field">
        <span>Symbols this site allows</span>
        <div className="box">
          <input
            className="mono"
            value={rules.symbols ?? DEFAULT_SYMBOLS}
            onChange={(event) =>
              setRules((current) => ({ ...current, symbols: event.target.value }))
            }
          />
        </div>
        <span className="hint">
          Delete the ones your site rejects. Some accept only a few and won't tell you which.
        </span>
      </label>

      <button
        type="button"
        className={rules.avoidLookalikes ? "btn pri big mono" : "btn sec big mono"}
        aria-pressed={rules.avoidLookalikes === true}
        onClick={() =>
          setRules((current) => ({ ...current, avoidLookalikes: !current.avoidLookalikes }))
        }
      >
        {rules.avoidLookalikes ? "✓ Leaving out look-alikes: " : "Leave out look-alikes: "}
        {/* Shown in the mono face with their categories: set in the UI font
            these look identical, which demonstrates the problem rather than
            explaining it. */}
        <CodeText value="l 1 I O 0" />
      </button>

      <fieldset className="generator-sets">
        <legend>Saved rules</legend>
        {[...PRESETS, ...saved].map((preset) => (
          <button
            key={preset.id}
            type="button"
            className={sameRules(preset, rules) ? "ring on" : "ring"}
            aria-pressed={sameRules(preset, rules)}
            onClick={() => setRules({ ...preset })}
          >
            {preset.name}
          </button>
        ))}
      </fieldset>

      {naming ? (
        <label className="field">
          <span>Name these rules</span>
          <div className="box">
            <input
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder="For example, My bank"
              autoFocus
            />
          </div>
          <button
            type="button"
            className="btn sec"
            disabled={name.trim().length === 0}
            onClick={() => {
              onSaveRules(name.trim(), rules);
              setName("");
              setNaming(false);
            }}
          >
            Save these rules
          </button>
        </label>
      ) : (
        <button type="button" className="btn sec" onClick={() => setNaming(true)}>
          Save these rules
        </button>
      )}

      <div className="sticky-actions stack">
        <button
          type="button"
          className="btn pri big"
          disabled={problem !== null || candidate.length === 0}
          onClick={() => onUse(candidate, rules)}
        >
          Use this password
        </button>
        <button type="button" className="btn sec big" onClick={onClose}>
          Cancel
        </button>
      </div>
    </section>
  );
}
