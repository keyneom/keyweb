/**
 * Locking Keyweb when nobody is using it.
 *
 * A browser tab stays open for days. Without this, passwords unlocked on
 * Monday were still one click away on Friday to whoever sat down at the
 * computer — the only lock was the one somebody remembered to press.
 *
 * Measured by the wall clock, never by a timer counting down. A background
 * tab's timers are slowed to once a minute or stopped altogether, and a
 * sleeping laptop runs none, so a countdown would come back from lunch still
 * waiting. Instead every check compares now against the last time someone
 * touched the page, and the check runs whenever it could matter: on a regular
 * tick, when the tab is shown again, and — before anything else — on the very
 * input that would otherwise count as use. Coming back after two hours and
 * clicking is not activity within the last fifteen minutes; it is the moment
 * to notice the two hours.
 */

export type LockAfter = "5" | "15" | "60" | "240";

export const LOCK_AFTER_DEFAULT: LockAfter = "15";

export const LOCK_AFTER_CHOICES: { value: LockAfter; label: string }[] = [
  { value: "5", label: "After 5 minutes" },
  { value: "15", label: "After 15 minutes" },
  { value: "60", label: "After 1 hour" },
  { value: "240", label: "After 4 hours" },
];

/** In words, for the lock screen: "15 minutes", "1 hour". */
export function describeLockAfter(value: LockAfter): string {
  const minutes = Number(value);
  if (minutes < 60) return `${minutes} minutes`;
  const hours = minutes / 60;
  return hours === 1 ? "1 hour" : `${hours} hours`;
}

const LOCK_AFTER_KEY = "keyweb:lock-after";

export function readLockAfter(): LockAfter {
  try {
    const stored = localStorage.getItem(LOCK_AFTER_KEY);
    return LOCK_AFTER_CHOICES.some((choice) => choice.value === stored)
      ? (stored as LockAfter)
      : LOCK_AFTER_DEFAULT;
  } catch {
    return LOCK_AFTER_DEFAULT;
  }
}

export function storeLockAfter(value: LockAfter): void {
  try {
    localStorage.setItem(LOCK_AFTER_KEY, value);
  } catch {
    /* A browser with storage blocked still gets the setting for this visit. */
  }
}

/** What a person does that counts as using the page. */
const ACTIVITY = ["pointerdown", "keydown", "wheel", "touchstart", "mousemove", "scroll"];

type Listenable = Pick<EventTarget, "addEventListener" | "removeEventListener">;

export type WatchIdleOptions = {
  timeoutMs: number;
  onIdle: () => void;
  /** Where input arrives. The window, in the app. */
  target?: Listenable;
  /** Where the tab being shown again is announced. The document, in the app. */
  doc?: Listenable & { visibilityState?: string };
  now?: () => number;
  /** How often to look while nothing else prompts it. */
  everyMs?: number;
  setInterval?: (run: () => void, ms: number) => unknown;
  clearInterval?: (handle: unknown) => void;
};

/**
 * Call `onIdle` once, the first time the page has gone `timeoutMs` without
 * being used. Returns a function that stops watching.
 */
export function watchIdle(options: WatchIdleOptions): () => void {
  const now = options.now ?? (() => Date.now());
  const target = options.target ?? window;
  const doc = options.doc ?? document;
  const every = options.everyMs ?? 15_000;
  const schedule = options.setInterval ?? ((run, ms) => window.setInterval(run, ms));
  const unschedule =
    options.clearInterval ?? ((handle) => window.clearInterval(handle as number));

  let last = now();
  let done = false;

  const stop = () => {
    if (done) return;
    done = true;
    unschedule(handle);
    for (const type of ACTIVITY) target.removeEventListener(type, onActivity, true);
    doc.removeEventListener("visibilitychange", onShown);
    target.removeEventListener("focus", onShown);
  };

  const check = (): boolean => {
    if (done) return true;
    if (now() - last < options.timeoutMs) return false;
    stop();
    options.onIdle();
    return true;
  };

  // Checked first: the input that ends a long absence is not use within it.
  function onActivity() {
    if (!check()) last = now();
  }

  function onShown() {
    if (doc.visibilityState === "hidden") return;
    check();
  }

  const handle = schedule(check, every);
  for (const type of ACTIVITY) target.addEventListener(type, onActivity, { capture: true, passive: true });
  doc.addEventListener("visibilitychange", onShown);
  target.addEventListener("focus", onShown);
  return stop;
}
