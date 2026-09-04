import { useCallback, useEffect, useState } from "react";

export type TextSize = "normal" | "large";
export type Appearance = "device" | "light" | "dark";

const TEXT_KEY = "keyweb:text-size";
const THEME_KEY = "keyweb:appearance";

function read<T extends string>(key: string, allowed: readonly T[], fallback: T): T {
  try {
    const stored = localStorage.getItem(key);
    return allowed.includes(stored as T) ? (stored as T) : fallback;
  } catch {
    return fallback;
  }
}

/**
 * Text size and light/dark, applied to the document root so every token-driven
 * component reflows at once. Larger text is a setting people go looking for, so
 * it lives on a screen with a word on it rather than behind a pinch gesture.
 */
export function useDisplaySettings() {
  const [textSize, setTextSizeState] = useState<TextSize>(() =>
    read(TEXT_KEY, ["normal", "large"] as const, "normal"),
  );
  const [appearance, setAppearanceState] = useState<Appearance>(() =>
    read(THEME_KEY, ["device", "light", "dark"] as const, "device"),
  );

  useEffect(() => {
    const root = document.documentElement;
    if (textSize === "large") root.setAttribute("data-text", "large");
    else root.removeAttribute("data-text");
  }, [textSize]);

  useEffect(() => {
    const root = document.documentElement;
    if (appearance === "device") root.removeAttribute("data-theme");
    else root.setAttribute("data-theme", appearance);
  }, [appearance]);

  const setTextSize = useCallback((value: TextSize) => {
    setTextSizeState(value);
    try {
      localStorage.setItem(TEXT_KEY, value);
    } catch {
      /* A browser with storage blocked still gets the setting for this visit. */
    }
  }, []);

  const setAppearance = useCallback((value: Appearance) => {
    setAppearanceState(value);
    try {
      localStorage.setItem(THEME_KEY, value);
    } catch {
      /* As above. */
    }
  }, []);

  return { textSize, setTextSize, appearance, setAppearance };
}
