import { useCallback, useRef } from "react";

/**
 * A press held long enough to mean something other than a tap.
 *
 * Built on pointer events rather than touch events so it works the same under
 * a finger, a mouse and a stylus. Three details decide whether it feels right:
 *
 *  - a small movement tolerance, because fingers never hold still, and
 *    cancelling on the first stray pixel makes the gesture feel broken;
 *  - cancelling when the finger travels further than that, so a scroll that
 *    starts on a row does not select it;
 *  - suppressing the click that the browser sends afterwards, or the row would
 *    both enter selection mode and open the password it was held on.
 */
export function useLongPress(onLongPress: () => void, delayMs = 450) {
  const timer = useRef<number | null>(null);
  const origin = useRef<{ x: number; y: number } | null>(null);
  const fired = useRef(false);

  const clear = useCallback(() => {
    if (timer.current !== null) window.clearTimeout(timer.current);
    timer.current = null;
    origin.current = null;
  }, []);

  const onPointerDown = useCallback(
    (event: React.PointerEvent) => {
      // Secondary buttons have their own meaning; leave them alone.
      if (event.button !== 0) return;
      fired.current = false;
      origin.current = { x: event.clientX, y: event.clientY };
      timer.current = window.setTimeout(() => {
        fired.current = true;
        clear();
        onLongPress();
      }, delayMs);
    },
    [clear, delayMs, onLongPress],
  );

  const onPointerMove = useCallback(
    (event: React.PointerEvent) => {
      const start = origin.current;
      if (start === null) return;
      const travelled = Math.hypot(event.clientX - start.x, event.clientY - start.y);
      if (travelled > 10) clear();
    },
    [clear],
  );

  const onClick = useCallback(
    (event: React.MouseEvent) => {
      if (!fired.current) return false;
      // The press already did its job; don't also open the row.
      event.preventDefault();
      event.stopPropagation();
      fired.current = false;
      return true;
    },
    [],
  );

  return {
    handlers: {
      onPointerDown,
      onPointerMove,
      onPointerUp: clear,
      onPointerLeave: clear,
      onPointerCancel: clear,
      // A held finger otherwise raises the platform's own menu on top of ours.
      onContextMenu: (event: React.MouseEvent) => event.preventDefault(),
    },
    /** True when this click follows a long press and should be swallowed. */
    swallowClick: onClick,
  };
}
