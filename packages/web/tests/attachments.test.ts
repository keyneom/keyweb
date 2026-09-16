import { describe, expect, it } from "vitest";
import {
  fromBase64,
  humanSize,
  isViewableImage,
  MAX_ATTACHMENT_BYTES,
  prepareFile,
  toBase64,
} from "../src/vault/attachments";
import { blobIdFor } from "../src/vault/keepass";

/**
 * Turning a picked file into something the vault can hold.
 *
 * The round trip is the part worth pinning: base64 that loses a byte gives
 * back a corrupt scan, and nothing on screen would say so — the image simply
 * fails to render months after the original was deleted.
 */
function file(name: string, bytes: Uint8Array, type = ""): File {
  return new File([bytes.buffer as ArrayBuffer], name, { type });
}

describe("preparing a file", () => {
  it("gives back exactly the bytes it was handed", async () => {
    const bytes = new Uint8Array(1024);
    crypto.getRandomValues(bytes);
    const prepared = await prepareFile(file("scan.png", bytes, "image/png"));
    expect([...fromBase64(prepared.data)]).toEqual([...bytes]);
    expect(prepared.bytes).toBe(1024);
    expect(prepared.type).toBe("image/png");
  });

  /** Content-addressed: the same bytes must give the same id, every time. */
  it("names a file by its contents, not by its name", async () => {
    const bytes = new Uint8Array([1, 2, 3, 4]);
    const a = await prepareFile(file("scan.png", bytes, "image/png"));
    const b = await prepareFile(file("totally-different.png", bytes, "image/png"));
    expect(b.blobId).toBe(a.blobId);
    expect(a.blobId).toBe(await blobIdFor(bytes));
  });

  it("gives different contents different ids", async () => {
    const a = await prepareFile(file("a", new Uint8Array([1, 2, 3])));
    const b = await prepareFile(file("b", new Uint8Array([1, 2, 4])));
    expect(a.blobId).not.toBe(b.blobId);
  });

  /**
   * Refused out loud rather than silently truncated, and rather than accepted
   * into a vault that then takes a second to save every password change.
   */
  it("refuses a file past the ceiling, and says how big it was", async () => {
    const big = new Uint8Array(MAX_ATTACHMENT_BYTES + 1);
    await expect(prepareFile(file("holiday.mp4", big, "video/mp4"))).rejects.toThrow(/10 MB/);
  });

  it("survives a file big enough to break a naive base64", async () => {
    // Spreading a multi-megabyte array into String.fromCharCode blows the
    // stack, which is the bug the chunked encoder exists to avoid.
    const bytes = new Uint8Array(2 * 1024 * 1024);
    // getRandomValues caps at 64 KiB a call, so fill it in chunks.
    for (let o = 0; o < bytes.length; o += 65536) {
      crypto.getRandomValues(bytes.subarray(o, Math.min(o + 65536, bytes.length)));
    }
    const round = fromBase64(toBase64(bytes));
    expect(round.byteLength).toBe(bytes.byteLength);
    expect(round[0]).toBe(bytes[0]);
    expect(round[round.length - 1]).toBe(bytes[bytes.length - 1]);
  });

  it("falls back to a type when the browser offers none", async () => {
    const prepared = await prepareFile(file("mystery", new Uint8Array([1])));
    expect(prepared.type).toBe("application/octet-stream");
  });
});

describe("what the app will render", () => {
  it("shows ordinary images", () => {
    expect(isViewableImage("image/png")).toBe(true);
    expect(isViewableImage("image/jpeg")).toBe(true);
  });

  /**
   * SVG is a document that can carry script and fetch remote content.
   * Rendering one from a vault would run somebody else's markup inside the
   * page holding every password, so it downloads like any other file.
   */
  it("refuses to render SVG", () => {
    expect(isViewableImage("image/svg+xml")).toBe(false);
  });

  it("refuses everything that is not an image", () => {
    expect(isViewableImage("application/pdf")).toBe(false);
    expect(isViewableImage("text/html")).toBe(false);
    expect(isViewableImage("")).toBe(false);
  });
});

describe("sizes people can read", () => {
  it("does not make somebody parse a byte count", () => {
    expect(humanSize(512)).toBe("512 bytes");
    expect(humanSize(2048)).toBe("2 KB");
    expect(humanSize(5 * 1024 * 1024)).toBe("5.0 MB");
  });
});
