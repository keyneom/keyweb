import { useCallback, useState } from "react";

/**
 * A list's ordering, remembered across visits.
 *
 * Somebody who prefers their keyrings biggest-first means it every time, not
 * once. It lives in `localStorage` rather than the vault because it is a
 * preference of this browser, not of the person's data — syncing it would put
 * a phone's ordering on a laptop nobody asked.
 *
 * Reads and writes are guarded: storage throws in a private window and in
 * embedded browsers, and a list that cannot be ordered because a preference
 * could not be read would be an absurd way to lose a screen.
 */
export function useRememberedSort<T extends string>(
  key: string,
  fallback: T,
): [T, (value: T) => void] {
  const [value, setValue] = useState<T>(() => {
    try {
      return (localStorage.getItem(`keyweb.${key}`) as T | null) ?? fallback;
    } catch {
      return fallback;
    }
  });

  const set = useCallback(
    (next: T) => {
      setValue(next);
      try {
        localStorage.setItem(`keyweb.${key}`, next);
      } catch {
        // The ordering still applies for this visit; only the memory is lost.
      }
    },
    [key],
  );

  return [value, set];
}
