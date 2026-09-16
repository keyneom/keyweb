import { blobIdFor, MAX_ATTACHMENT_BYTES } from "./keepass";

/**
 * Turning a file the person picked into something the vault can hold.
 *
 * The bytes go in as base64 on an item of their own — see `BLOB_KIND` in
 * vault-core for why a file is an ordinary item rather than a separate store.
 * Everything here is the conversion either side of that.
 */

export { MAX_ATTACHMENT_BYTES };

export class FileTooBig extends Error {
  constructor(name: string, bytes: number) {
    super(
      `${name} is ${(bytes / 1024 / 1024).toFixed(1)} MB. Keyweb can hold files up to ${
        MAX_ATTACHMENT_BYTES / 1024 / 1024
      } MB — anything larger would make saving a password slow every time.`,
    );
    this.name = "FileTooBig";
  }
}

export type PreparedFile = {
  blobId: string;
  name: string;
  type: string;
  data: string;
  bytes: number;
};

/** Read a picked file and content-address it, ready to attach. */
export async function prepareFile(file: File): Promise<PreparedFile> {
  if (file.size > MAX_ATTACHMENT_BYTES) throw new FileTooBig(file.name, file.size);
  const bytes = new Uint8Array(await file.arrayBuffer());
  return {
    blobId: await blobIdFor(bytes),
    name: file.name,
    // Browsers leave this empty for types they do not recognise; the viewer
    // treats an empty type as "offer to download" rather than guessing.
    type: file.type || "application/octet-stream",
    data: toBase64(bytes),
    bytes: bytes.byteLength,
  };
}

/**
 * A URL the browser can render, made from the stored base64.
 *
 * An object URL rather than a `data:` one: a multi-megabyte data URL in an
 * `src` attribute is held as a string by the DOM as well as by us, and shows
 * up in devtools and in any error report that serialises the element.
 * Object URLs are opaque handles, and `revokeAttachmentUrl` takes them back.
 */
export function attachmentUrl(data: string, type: string): string {
  const bytes = fromBase64(data);
  return URL.createObjectURL(new Blob([bytes.buffer as ArrayBuffer], { type }));
}

export function revokeAttachmentUrl(url: string): void {
  URL.revokeObjectURL(url);
}

/** True for something the app can show rather than only hand over. */
export function isViewableImage(type: string): boolean {
  return type.startsWith("image/") && type !== "image/svg+xml";
}

/**
 * SVG is deliberately not viewable.
 *
 * It is a document that can carry script and fetch remote content, and
 * rendering one from a vault would run somebody else's markup inside the page
 * holding every password. It downloads like any other file instead.
 */

export function toBase64(bytes: Uint8Array): string {
  let binary = "";
  // Chunked: spreading a multi-megabyte array into apply blows the stack.
  for (let i = 0; i < bytes.length; i += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  }
  return btoa(binary);
}

export function fromBase64(value: string): Uint8Array {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

/** A size somebody can judge at a glance, rather than a byte count. */
export function humanSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} bytes`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}
