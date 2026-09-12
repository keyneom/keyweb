import { useCallback, useState } from "react";
import { PRESETS, type PasswordRules, type SavedRules } from "@keyweb/vault-core";

/**
 * Rule sets someone named and kept, and whatever the generator was last set to.
 *
 * Held in this browser's own storage rather than in the vault. They are a
 * convenience, not a secret, and putting them in the synced document would mean
 * merging two devices' disagreement about a slider position. The trade is that
 * they do not follow you to another device yet, which is worth saying out loud
 * rather than discovering.
 *
 * Keys are namespaced, because GitHub Pages project sites share one origin and
 * an unprefixed key would collide with whatever else is published there.
 */
const SAVED_KEY = "keyweb:generator-rules";
const LAST_KEY = "keyweb:generator-last";

function read<T>(key: string, fallback: T): T {
  try {
    const stored = localStorage.getItem(key);
    return stored ? (JSON.parse(stored) as T) : fallback;
  } catch {
    // Private browsing, cleared storage, or a value from an older shape. A
    // forgotten preset is a small loss; a crash on unlock is not.
    return fallback;
  }
}

function write(key: string, value: unknown): void {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // Storage can be full or blocked. The rules still apply for this session.
  }
}

export function useGeneratorRules() {
  const [savedRules, setSavedRules] = useState<SavedRules[]>(() => read(SAVED_KEY, []));
  const [lastRules, setLastRules] = useState<PasswordRules>(() => read(LAST_KEY, PRESETS[0]!));

  const saveRules = useCallback((name: string, rules: PasswordRules) => {
    setSavedRules((current) => {
      // Re-saving under an existing name replaces it, rather than stacking up
      // near-identical entries nobody can tell apart.
      const next = [
        ...current.filter((entry) => entry.name.toLowerCase() !== name.toLowerCase()),
        { ...rules, id: crypto.randomUUID(), name },
      ];
      write(SAVED_KEY, next);
      return next;
    });
  }, []);

  const rememberLastRules = useCallback((rules: PasswordRules) => {
    write(LAST_KEY, rules);
    setLastRules(rules);
  }, []);

  return { savedRules, lastRules, saveRules, rememberLastRules };
}
