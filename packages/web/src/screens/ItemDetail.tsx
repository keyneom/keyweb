import { useEffect, useState } from "react";
import { itemField, type ItemRecord, type VaultState } from "@keyweb/vault-core";
import { BackIcon, CopyIcon, EyeIcon } from "../ui/icons";

const CLIPBOARD_CLEAR_SECONDS = 45;
const REVEAL_SECONDS = 30;

export function ItemDetail({
  item,
  state,
  onBack,
  onEdit,
  onDelete,
  onCopied,
}: {
  item: ItemRecord;
  state: VaultState;
  onBack: () => void;
  onEdit: () => void;
  onDelete: () => void;
  onCopied: (message: string) => void;
}) {
  const [revealed, setRevealed] = useState(false);
  const title = itemField(item, "title") ?? "Untitled";
  const username = itemField(item, "username") ?? "";
  const password = itemField(item, "password") ?? "";
  const url = itemField(item, "url") ?? "";
  const note = itemField(item, "note") ?? "";
  const ring = state.keyrings[item.keyring.value];

  // Auto-hide, so a revealed password doesn't sit on screen indefinitely.
  useEffect(() => {
    if (!revealed) return;
    const timer = setTimeout(() => setRevealed(false), REVEAL_SECONDS * 1000);
    return () => clearTimeout(timer);
  }, [revealed]);

  async function copy(value: string, label: string) {
    try {
      await navigator.clipboard.writeText(value);
      onCopied(`${label} copied. It will clear from your clipboard in ${CLIPBOARD_CLEAR_SECONDS} seconds.`);
      setTimeout(() => {
        void navigator.clipboard.writeText("").catch(() => undefined);
      }, CLIPBOARD_CLEAR_SECONDS * 1000);
    } catch {
      onCopied("Your browser wouldn't let us copy that. Show it and copy by hand.");
    }
  }

  return (
    <>
      <header className="topbar">
        <button type="button" className="iconbtn" onClick={onBack}>
          <BackIcon />
          Back
        </button>
      </header>

      <h1 className="screen-title">{title}</h1>
      <p className="screen-sub">{ring ? `On the ${ring.name.value} keyring` : "No keyring"}</p>

      {username && (
        <label className="field">
          <span>Username</span>
          <div className="box">
            <input value={username} readOnly aria-label="Username" />
            <button type="button" className="iconbtn" onClick={() => void copy(username, "Username")}>
              <CopyIcon />
              Copy
            </button>
          </div>
        </label>
      )}

      <label className="field">
        <span>Password</span>
        <div className="box">
          <input
            className="mono"
            type={revealed ? "text" : "password"}
            value={password}
            readOnly
            aria-label="Password"
          />
          <button type="button" className="iconbtn" onClick={() => setRevealed((v) => !v)}>
            <EyeIcon />
            {revealed ? "Hide" : "Show"}
          </button>
          <button type="button" className="iconbtn" onClick={() => void copy(password, "Password")}>
            <CopyIcon />
            Copy
          </button>
        </div>
        <span className="hint">
          {revealed
            ? `Hiding again in ${REVEAL_SECONDS} seconds.`
            : "Hidden until you choose to show it."}
        </span>
      </label>

      {url && (
        <label className="field">
          <span>Website</span>
          <div className="box">
            <input value={url} readOnly aria-label="Website" />
          </div>
        </label>
      )}

      {note && (
        <label className="field">
          <span>Note</span>
          <div className="box">
            <input value={note} readOnly aria-label="Note" />
          </div>
        </label>
      )}

      <div className="stack">
        <button type="button" className="btn sec big" onClick={onEdit}>
          Edit
        </button>
        <button type="button" className="btn danger big" onClick={onDelete}>
          Delete this password
        </button>
      </div>
    </>
  );
}
