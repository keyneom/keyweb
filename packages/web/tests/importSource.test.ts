import { beforeEach, describe, expect, it } from "vitest";
import {
  forgetSource,
  markImported,
  mergeSources,
  readSources,
  rememberSources,
  type ImportSource,
} from "../src/vault/importSource";

function source(fileId: string, lastImportedAt: number | null = null): ImportSource {
  return { fileId, name: `${fileId}.kdbx`, lastImportedAt };
}

describe("remembering which files were handed over", () => {
  beforeEach(() => {
    // vitest runs in node, where localStorage is absent unless provided.
    const store = new Map<string, string>();
    globalThis.localStorage = {
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => void store.set(k, v),
      removeItem: (k: string) => void store.delete(k),
      clear: () => store.clear(),
      key: () => null,
      length: 0,
    } as unknown as Storage;
  });

  it("round-trips through storage", () => {
    rememberSources([source("a"), source("b", 5)]);
    expect(readSources().map((s) => s.fileId)).toEqual(["a", "b"]);
  });

  it("survives storage being unavailable rather than failing the import", () => {
    globalThis.localStorage = {
      getItem: () => {
        throw new Error("blocked");
      },
      setItem: () => {
        throw new Error("blocked");
      },
    } as unknown as Storage;
    // Private browsing must cost the shortcut, not the feature.
    expect(readSources()).toEqual([]);
    expect(() => rememberSources([source("a")])).not.toThrow();
  });

  it("ignores stored junk instead of crashing on unlock", () => {
    localStorage.setItem("keyweb:import-sources", "not json");
    expect(readSources()).toEqual([]);
    localStorage.setItem("keyweb:import-sources", '{"not":"an array"}');
    expect(readSources()).toEqual([]);
  });

  it("keeps the import history when the same file is picked again", () => {
    // Re-picking is how someone re-grants access, and it must not look like a
    // file they have never imported.
    const existing = [source("a", 100)];
    const merged = mergeSources(existing, [source("a")]);
    expect(merged).toHaveLength(1);
    expect(merged[0]!.lastImportedAt).toBe(100);
  });

  it("adds newly picked files without disturbing the others", () => {
    const merged = mergeSources([source("a", 100)], [source("b")]);
    expect(merged.map((s) => s.fileId).sort()).toEqual(["a", "b"]);
  });

  it("puts the most recently imported file first", () => {
    const sources = mergeSources([], [source("a"), source("b"), source("c")]);
    const after = markImported(markImported(sources, "c", 10), "b", 20);
    expect(after.map((s) => s.fileId)).toEqual(["b", "c", "a"]);
  });

  it("forgetting a file only drops the shortcut", () => {
    const sources = mergeSources([], [source("a"), source("b")]);
    const after = forgetSource(sources, "a");
    expect(after.map((s) => s.fileId)).toEqual(["b"]);
  });
});
