import type { VaultState } from "./model.js";
import { emptyVault } from "./model.js";
import type { RemoteRevision, RemoteVaultStore } from "./storage.js";
import { RemoteUnavailableError, VersionConflictError } from "./storage.js";

/**
 * A remote that behaves the way Google Drive behaves at its worst: reads and
 * writes take real time, another device can land a revision mid-flight, and
 * the connection can vanish. Used to prove the sync engine under conditions we
 * cannot reproduce on demand against live Drive.
 */
export class FakeRemote implements RemoteVaultStore {
  #state: VaultState | null = null;
  #version = 0;

  /** Resolves during the gap between read and write, to inject a race. */
  onBeforeWrite: (() => Promise<void> | void) | null = null;
  readDelayMs = 0;
  writeDelayMs = 0;
  offline = false;
  /** Reject the next N writes with a version conflict, whatever the token. */
  forceConflicts = 0;

  writes = 0;
  rejectedWrites = 0;

  async read(): Promise<RemoteRevision | null> {
    if (this.offline) throw new RemoteUnavailableError();
    await delay(this.readDelayMs);
    if (this.#state === null) return null;
    return { state: this.#state, version: String(this.#version) };
  }

  async write(state: VaultState, expectedVersion: string | null): Promise<string> {
    if (this.offline) throw new RemoteUnavailableError();
    if (this.onBeforeWrite) await this.onBeforeWrite();
    await delay(this.writeDelayMs);

    if (this.forceConflicts > 0) {
      this.forceConflicts -= 1;
      this.rejectedWrites += 1;
      throw new VersionConflictError();
    }
    const current = this.#state === null ? null : String(this.#version);
    if (current !== expectedVersion) {
      this.rejectedWrites += 1;
      throw new VersionConflictError(
        `Expected version ${expectedVersion ?? "none"} but the remote is at ${current ?? "none"}.`,
      );
    }
    this.#state = state;
    this.#version += 1;
    this.writes += 1;
    return String(this.#version);
  }

  /** Simulate another device publishing a revision behind our back. */
  landForeignRevision(state: VaultState): void {
    this.#state = state;
    this.#version += 1;
  }

  snapshot(): VaultState {
    return this.#state ?? emptyVault();
  }
}

export function delay(ms: number): Promise<void> {
  return ms <= 0 ? Promise.resolve() : new Promise((resolve) => setTimeout(resolve, ms));
}

/** Deterministic id generator so failures are reproducible. */
export function seqIds(prefix: string): () => string {
  let n = 0;
  return () => `${prefix}-${++n}`;
}

/** A controllable wall clock, for proving behaviour under clock skew. */
export function fakePhysical(startMs: number): { read: () => number; set: (ms: number) => void } {
  let value = startMs;
  return {
    read: () => value,
    set: (ms: number) => {
      value = ms;
    },
  };
}
