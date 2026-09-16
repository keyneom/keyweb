import { useEffect, useRef, useState } from "react";
import { attachmentUrl, humanSize, isViewableImage, revokeAttachmentUrl } from "../vault/attachments";
import { BackIcon } from "../ui/icons";

/**
 * Looking at a file kept in the vault.
 *
 * Buttons rather than gestures for the zoom. Pinch and wheel work too and are
 * what most people will reach for, but a zoom that can *only* be reached by
 * pinching is a zoom that does not exist for somebody using a mouse, one hand,
 * or a trackpad they have never configured — and reading a scanned document is
 * exactly when somebody needs it to be bigger.
 *
 * Nothing here ever touches the network. The bytes come from the vault, become
 * an object URL for as long as this screen is open, and are handed back the
 * moment it closes: an image left in an object URL outlives the lock screen.
 */
const STEPS = [1, 1.5, 2, 3, 4, 6];

export function FileViewer({
  name,
  type,
  data,
  bytes,
  onClose,
}: {
  name: string;
  type: string;
  data: string;
  bytes: number;
  onClose: () => void;
}) {
  const [url, setUrl] = useState<string | null>(null);
  const [step, setStep] = useState(0);
  const [pan, setPan] = useState({ x: 0, y: 0 });
  const dragging = useRef<{ x: number; y: number } | null>(null);
  const viewable = isViewableImage(type);

  // Made on open and handed back on close, so the bytes are not left addressable
  // by a URL after the vault is locked.
  useEffect(() => {
    if (!viewable) return;
    const created = attachmentUrl(data, type);
    setUrl(created);
    return () => {
      revokeAttachmentUrl(created);
      setUrl(null);
    };
  }, [data, type, viewable]);

  // Escape closes, which is what every other full-screen thing in a browser does.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const scale = STEPS[step] ?? 1;

  function zoom(direction: 1 | -1) {
    setStep((current) => Math.min(STEPS.length - 1, Math.max(0, current + direction)));
    if (direction === -1) setPan({ x: 0, y: 0 });
  }

  function download() {
    const href = attachmentUrl(data, type);
    const link = document.createElement("a");
    link.href = href;
    link.download = name;
    link.click();
    // Given back on the next turn, once the browser has taken what it needs.
    setTimeout(() => revokeAttachmentUrl(href), 0);
  }

  return (
    <section className="viewer">
      <header className="topbar">
        <button type="button" className="iconbtn" onClick={onClose}>
          <BackIcon />
          Back
        </button>
      </header>

      <h1 className="screen-title">{name}</h1>
      <p className="screen-sub">
        {humanSize(bytes)}
        {viewable ? ` · ${Math.round(scale * 100)}%` : " · kept in your vault"}
      </p>

      {viewable && url ? (
        <div
          className="viewer-stage"
          onWheel={(event) => {
            if (!event.ctrlKey && Math.abs(event.deltaY) < 2) return;
            zoom(event.deltaY < 0 ? 1 : -1);
          }}
          onPointerDown={(event) => {
            if (scale === 1) return;
            dragging.current = { x: event.clientX - pan.x, y: event.clientY - pan.y };
            event.currentTarget.setPointerCapture(event.pointerId);
          }}
          onPointerMove={(event) => {
            const from = dragging.current;
            if (!from) return;
            setPan({ x: event.clientX - from.x, y: event.clientY - from.y });
          }}
          onPointerUp={() => {
            dragging.current = null;
          }}
        >
          <img
            src={url}
            alt={name}
            draggable={false}
            style={{
              transform: `translate(${pan.x}px, ${pan.y}px) scale(${scale})`,
              cursor: scale > 1 ? "grab" : "default",
            }}
          />
        </div>
      ) : (
        <p className="status" data-tone="calm">
          <span>
            <b>Keyweb can't show this kind of file.</b>
            <em>
              It is safely in your vault. Save it to this device to open it in whatever app
              normally handles it.
            </em>
          </span>
        </p>
      )}

      {viewable && (
        <div className="stack">
          <button
            type="button"
            className="btn sec big"
            onClick={() => zoom(1)}
            disabled={step === STEPS.length - 1}
          >
            Make it bigger
          </button>
          <button
            type="button"
            className="btn sec big"
            onClick={() => zoom(-1)}
            disabled={step === 0}
          >
            Make it smaller
          </button>
        </div>
      )}

      <button type="button" className="btn pri big" onClick={download}>
        Save it to this device
      </button>
      <p className="hint warn">
        Saved files leave the vault. Anything that can read your downloads can read this.
      </p>
    </section>
  );
}
