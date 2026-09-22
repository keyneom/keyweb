import { describe, expect, it } from "vitest";
import { describeLockAfter, watchIdle } from "../src/vault/idleLock";

/**
 * Keyweb locks itself when nobody is using it, by the wall clock.
 *
 * The clock and the tick are both driven by hand here, because the cases that
 * matter are the ones where the browser does *not* tick: a background tab, a
 * sleeping laptop.
 */
function harness(timeoutMs = 15 * 60_000) {
  let clock = 1_000_000;
  let tick: (() => void) | null = null;
  const target = new EventTarget();
  const doc = Object.assign(new EventTarget(), { visibilityState: "visible" });
  let idled = 0;
  const stop = watchIdle({
    timeoutMs,
    onIdle: () => idled++,
    target,
    doc,
    now: () => clock,
    setInterval: (run) => {
      tick = run;
      return 1;
    },
    clearInterval: () => {
      tick = null;
    },
  });
  return {
    advance: (ms: number) => {
      clock += ms;
    },
    tick: () => tick?.(),
    ticking: () => tick !== null,
    use: (type = "pointerdown") => target.dispatchEvent(new Event(type)),
    show: () => doc.dispatchEvent(new Event("visibilitychange")),
    idled: () => idled,
    stop,
  };
}

describe("locking when nobody is using it", () => {
  it("locks once the time has passed with nothing touched", () => {
    const page = harness();
    page.advance(14 * 60_000);
    page.tick();
    expect(page.idled()).toBe(0);
    page.advance(60_000);
    page.tick();
    expect(page.idled()).toBe(1);
  });

  it("stays open while someone keeps using it", () => {
    const page = harness();
    for (let minute = 0; minute < 60; minute++) {
      page.advance(60_000);
      page.use(minute % 2 ? "keydown" : "scroll");
      page.tick();
    }
    expect(page.idled()).toBe(0);
  });

  it("locks on the click that ends a long absence, not after it", () => {
    // A background tab whose tick never ran: the click is the first chance to
    // notice, and it must not count as having been there all along.
    const page = harness();
    page.advance(2 * 60 * 60_000);
    page.use();
    expect(page.idled()).toBe(1);
  });

  it("locks the moment the tab is shown again after sleeping", () => {
    const page = harness();
    page.advance(3 * 60 * 60_000);
    page.show();
    expect(page.idled()).toBe(1);
  });

  it("locks only once, and stops watching when it does", () => {
    const page = harness();
    page.advance(20 * 60_000);
    page.tick();
    page.use();
    page.show();
    expect(page.idled()).toBe(1);
    expect(page.ticking()).toBe(false);
  });

  it("does nothing after being stopped, as when locked by hand", () => {
    const page = harness();
    page.stop();
    page.advance(60 * 60_000);
    page.use();
    page.show();
    expect(page.idled()).toBe(0);
  });

  it("says the time the way a person would", () => {
    expect(describeLockAfter("5")).toBe("5 minutes");
    expect(describeLockAfter("60")).toBe("1 hour");
    expect(describeLockAfter("240")).toBe("4 hours");
  });
});
