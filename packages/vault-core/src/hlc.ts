/**
 * Hybrid Logical Clock.
 *
 * Last-write-wins ordered by `Date.now()` is itself a data-loss bug: a device
 * whose clock runs slow can never win a merge, so its edits are silently
 * discarded forever. An HLC keeps wall-clock readability but guarantees that
 * an edit which *causally follows* another always sorts after it, whatever the
 * two devices' clocks say.
 *
 * Encoded as a lexicographically sortable string so it can be compared with
 * `<` and stored in JSON without a custom comparator:
 *
 *   000001723456789-00003-a1b2c3d4
 *   \_____________/ \___/ \______/
 *      wall ms     counter  node
 */

const WALL_DIGITS = 15;
const COUNTER_DIGITS = 5;
const MAX_COUNTER = 10 ** COUNTER_DIGITS - 1;

/** A point in causal time. Compare these with `<`, `>`, or `compareHlc`. */
export type Hlc = string;

export type HlcParts = { wall: number; counter: number; node: string };

export function encodeHlc(parts: HlcParts): Hlc {
  const wall = String(parts.wall).padStart(WALL_DIGITS, "0");
  const counter = String(parts.counter).padStart(COUNTER_DIGITS, "0");
  return `${wall}-${counter}-${parts.node}`;
}

export function decodeHlc(value: Hlc): HlcParts {
  const wall = value.slice(0, WALL_DIGITS);
  const counter = value.slice(WALL_DIGITS + 1, WALL_DIGITS + 1 + COUNTER_DIGITS);
  const node = value.slice(WALL_DIGITS + COUNTER_DIGITS + 2);
  const parsedWall = Number.parseInt(wall, 10);
  const parsedCounter = Number.parseInt(counter, 10);
  if (Number.isNaN(parsedWall) || Number.isNaN(parsedCounter) || node.length === 0) {
    throw new Error(`Malformed HLC timestamp: ${value}`);
  }
  return { wall: parsedWall, counter: parsedCounter, node };
}

export function compareHlc(a: Hlc, b: Hlc): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

/** The zero timestamp — sorts before every real one. Used for absent values. */
export const HLC_ZERO: Hlc = encodeHlc({ wall: 0, counter: 0, node: "" });

export interface Clock {
  /** Stamp a new local event. */
  now(): Hlc;
  /** Observe a remote timestamp, advancing local causal time past it. */
  observe(remote: Hlc): void;
  /** Current state, for persistence across restarts. */
  snapshot(): Hlc;
}

export function createClock(options: {
  node: string;
  physical?: () => number;
  /** Restore causal time after a restart so a fast local clock can't be undone. */
  resume?: Hlc | undefined;
}): Clock {
  const physical = options.physical ?? (() => Date.now());
  if (options.node.length === 0) throw new Error("An HLC needs a non-empty node id.");

  let last: HlcParts = options.resume
    ? { ...decodeHlc(options.resume), node: options.node }
    : { wall: 0, counter: 0, node: options.node };

  function advance(candidateWall: number, candidateCounter: number): Hlc {
    if (candidateCounter > MAX_COUNTER) {
      // Counter saturation means >100k events in one millisecond. Borrow from
      // the next millisecond rather than wrapping, which would break ordering.
      last = { wall: candidateWall + 1, counter: 0, node: options.node };
    } else {
      last = { wall: candidateWall, counter: candidateCounter, node: options.node };
    }
    return encodeHlc(last);
  }

  return {
    now() {
      const wall = physical();
      if (wall > last.wall) return advance(wall, 0);
      return advance(last.wall, last.counter + 1);
    },
    observe(remote: Hlc) {
      const parts = decodeHlc(remote);
      const wall = physical();
      const maxWall = Math.max(wall, last.wall, parts.wall);
      let counter: number;
      if (maxWall === last.wall && maxWall === parts.wall) {
        counter = Math.max(last.counter, parts.counter) + 1;
      } else if (maxWall === last.wall) {
        counter = last.counter + 1;
      } else if (maxWall === parts.wall) {
        counter = parts.counter + 1;
      } else {
        counter = 0;
      }
      advance(maxWall, counter);
    },
    snapshot() {
      return encodeHlc(last);
    },
  };
}
