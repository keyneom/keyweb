import { useEffect, useState } from "react";
import {
  isSecretField,
  itemField,
  type ItemField,
  type ItemRecord,
  type VaultState,
} from "@keyweb/vault-core";
import { CodeLegend, CodeText } from "../ui/CodeText";
import { BackIcon, CopyIcon, EyeIcon } from "../ui/icons";

/**
 * The fields this screen already lays out by hand, above.
 *
 * `folder` and `tags` are structure rather than content and are shown in the
 * list instead; `kind` picks the template. Everything not named here gets the
 * generic treatment, which is what makes an imported field visible at all.
 */
const PRESENTED: readonly ItemField[] = ["title", "username", "password", "url", "note", "folder", "tags", "kind"];

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

  /*
   * Everything else this item carries.
   *
   * The screen used to render five fields and no more, which meant a vault
   * could hold a security answer or a backup PIN — imported, synced, backed
   * up — that its owner could never see. `ItemField` has always been
   * open-keyed precisely so a field nobody anticipated survives; showing them
   * is the other half of that promise.
   */
  const extras = Object.keys(item.fields)
    .filter((name) => !(PRESENTED as readonly string[]).includes(name))
    .filter((name) => (itemField(item, name) ?? "") !== "")
    .sort();

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
          {/*
            Rendered text rather than an input, so each character can be drawn
            in the colour of its category. Nothing is lost: the field was always
            read-only, `user-select: all` still makes it selectable in one tap,
            and dropping the password input stops the browser offering to save a
            password it is only being shown.
          */}
          {revealed ? (
            <CodeText value={password} className="mono secret-value" />
          ) : (
            <span className="mono secret-value" aria-label="Password, hidden">
              {"\u2022".repeat(Math.min(password.length, 24))}
            </span>
          )}
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
      {/* Only while something is actually on screen to decode. */}
      {revealed && <CodeLegend />}

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

      {extras.map((name) => (
        <ExtraField
          key={name}
          name={name}
          value={itemField(item, name) ?? ""}
          onCopy={(value, label) => void copy(value, label)}
        />
      ))}

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

/**
 * A field Keyweb has no special presentation for.
 *
 * Masked when the name says it is a secret, which for anything imported from
 * KeePass means the field its owner marked protected: those arrive as
 * `secret:<name>`, so an answer to "first pet's name" is hidden here exactly
 * as it was hidden there.
 */
function ExtraField({
  name,
  value,
  onCopy,
}: {
  name: string;
  value: string;
  onCopy: (value: string, label: string) => void;
}) {
  const [shown, setShown] = useState(false);
  const secret = isSecretField(name);
  const label = name.replace(/^secret:/, "").replace(/^custom:/, "");

  return (
    <label className="field">
      <span>{label}</span>
      <div className="box">
        {secret && !shown ? (
          <span className="mono secret-value" aria-label={`${label}, hidden`}>
            {"\u2022".repeat(Math.min(value.length, 24))}
          </span>
        ) : (
          <input value={value} readOnly aria-label={label} />
        )}
        {secret && (
          <button type="button" className="iconbtn" onClick={() => setShown((v) => !v)}>
            <EyeIcon />
            {shown ? "Hide" : "Show"}
          </button>
        )}
        <button type="button" className="iconbtn" onClick={() => onCopy(value, label)}>
          <CopyIcon />
          Copy
        </button>
      </div>
    </label>
  );
}
