import jsQR from "jsqr";

/**
 * Reading a QR code in a browser.
 *
 * Two decoders, in that order, and the order is the whole design:
 *
 *  - `BarcodeDetector` is the platform's own, hardware-accelerated where the
 *    hardware exists, and present in Chrome and Edge. When it is there it is
 *    better than anything shipped in a bundle.
 *  - `jsQR` is the fallback, because Safari and Firefox do not implement
 *    `BarcodeDetector` at all and "your browser can't do this" is not an
 *    acceptable answer for the one path that brings somebody's second factors
 *    across.
 *
 * Both run entirely in the page. No frame, no pixel and no decoded string
 * leaves the device — which matters more here than almost anywhere else in the
 * app, because what is in front of the camera is a list of authentication
 * secrets.
 */

type Detector = { detect(source: ImageBitmapSource): Promise<{ rawValue: string }[]> };

/**
 * The platform decoder, if this browser has one that can read QR.
 *
 * `getSupportedFormats` is asked rather than assumed: the API exists on some
 * builds with only a subset of formats, and a detector that cannot read QR is
 * worse than no detector because it silently finds nothing.
 */
async function platformDetector(): Promise<Detector | null> {
  const ctor = (globalThis as { BarcodeDetector?: new (o: object) => Detector } & {
    BarcodeDetector?: { getSupportedFormats?: () => Promise<string[]> };
  }).BarcodeDetector as
    | (new (o: object) => Detector & { getSupportedFormats?: () => Promise<string[]> })
    | undefined;
  if (!ctor) return null;
  try {
    const formats = await (
      ctor as unknown as { getSupportedFormats?: () => Promise<string[]> }
    ).getSupportedFormats?.();
    if (formats && !formats.includes("qr_code")) return null;
    return new ctor({ formats: ["qr_code"] });
  } catch {
    return null;
  }
}

/** Whatever a QR code in this image says, or null if there isn't one. */
export async function readQrFromImage(source: CanvasImageSource & ImageBitmapSource): Promise<string | null> {
  const detector = await platformDetector();
  if (detector) {
    try {
      const found = await detector.detect(source);
      const value = found[0]?.rawValue;
      if (value) return value;
    } catch {
      // Fall through to jsQR rather than failing: a detector that throws on
      // one frame is not a reason to stop reading frames.
    }
  }

  const pixels = rasterise(source);
  if (!pixels) return null;
  const found = jsQR(pixels.data, pixels.width, pixels.height, {
    // The codes here are dark-on-light on a phone screen; asking for both
    // roughly doubles the work per frame for cases that do not arise.
    inversionAttempts: "dontInvert",
  });
  return found?.data ?? null;
}

/**
 * The pixels behind an image, video frame or bitmap.
 *
 * `willReadFrequently` because this runs on every video frame while the camera
 * is open, and without it the browser keeps the canvas on the GPU and each
 * read back stalls the pipeline.
 */
function rasterise(source: CanvasImageSource): ImageData | null {
  const width = naturalWidth(source);
  const height = naturalHeight(source);
  if (!width || !height) return null;

  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext("2d", { willReadFrequently: true });
  if (!context) return null;
  context.drawImage(source, 0, 0, width, height);
  try {
    return context.getImageData(0, 0, width, height);
  } catch {
    // A tainted canvas, which cannot happen for a camera stream or a file the
    // person chose, but would for an image from another origin.
    return null;
  }
}

function naturalWidth(source: CanvasImageSource): number {
  const value = source as { videoWidth?: number; naturalWidth?: number; width?: number };
  return value.videoWidth || value.naturalWidth || (typeof value.width === "number" ? value.width : 0);
}

function naturalHeight(source: CanvasImageSource): number {
  const value = source as { videoHeight?: number; naturalHeight?: number; height?: number };
  return (
    value.videoHeight || value.naturalHeight || (typeof value.height === "number" ? value.height : 0)
  );
}

/** Whatever a QR code in this file says. Used when there is no camera. */
export async function readQrFromFile(file: File): Promise<string | null> {
  const url = URL.createObjectURL(file);
  try {
    const image = new Image();
    image.src = url;
    await image.decode();
    return await readQrFromImage(image);
  } catch {
    return null;
  } finally {
    URL.revokeObjectURL(url);
  }
}
