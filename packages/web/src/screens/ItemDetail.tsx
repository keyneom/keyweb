import { useEffect, useState } from "react";
import {
  attachmentsOf,
  fieldLabel,
  isSecretField,
  pastValues,
  type PastValue,
  itemField,
  type ItemField,
  type ItemRecord,
  type VaultState,
} from "@keyweb/vault-core";
import { CodeLegend, CodeText } from "../ui/CodeText";
import { Disclosure } from "../ui/Disclosure";
import { OtpCode } from "../ui/OtpCode";
import { attachmentUrl, humanSize, isViewableImage, revokeAttachmentUrl } from "../vault/attachments";
import { BackIcon, CopyIcon, EyeIcon } from "../ui/icons";

/**
 * The fields this screen already lays out by hand, above.
 *
 * `folder` and `tags` are structure rather than content and are shown in the
 * list instead; `kind` picks the template. Everything not named here gets the
 * generic treatment, which is what makes an imported field visible at all.
 */
const PRESENTED: readonly ItemField[] = [
  "title",
  "username",
  "password",
  "url",
  "note",
  // Shown as a rotating code by `OtpCode`, not as a field of text.
  "otp",
  "folder",
  "tags",
  "kind",
];

const CLIPBOARD_CLEAR_SECONDS = 45;
const REVEAL_SECONDS = 30;

export function ItemDetail({
  item,
  state,
  onBack,
  onEdit,
  onDelete,
  onCopied,
  onOpenFile,
  onAttach,
  onRemoveFile,
  onRestore,
}: {
  item: ItemRecord;
  state: VaultState;
  onBack: () => void;
  onEdit: () => void;
  onDelete: () => void;
  onCopied: (message: string) => void;
  onOpenFile: (blobId: string) => void;
  onAttach: (file: File) => Promise<void>;
  onRemoveFile: (blobId: string) => Promise<void>;
  onRestore: (field: string, value: string) => void;
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
    // The pointer at an attached file is plumbing, not a field somebody wrote.
    // It is rendered by `Files` below as the file it names.
    .filter((name) => !name.startsWith("file:"))
    .filter((name) => (itemField(item, name) ?? "") !== "")
    // By the name on screen, not the key behind it: sorting on the key put
    // every hidden field in a block of its own under "s", which is an ordering
    // nobody typing these names would expect.
    .sort((a, b) => fieldLabel(a).localeCompare(fieldLabel(b)));

  const past = pastValues(item);

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

      {itemField(item, "otp") && (
        <OtpCode secret={itemField(item, "otp")!} onCopy={(value, label) => void copy(value, label)} />
      )}

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

      <Files
        item={item}
        state={state}
        onOpen={onOpenFile}
        onAttach={onAttach}
        onRemove={onRemoveFile}
        onError={onCopied}
      />

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
      </div>

      {past.length > 0 && (
        <Disclosure
          label="What this used to be"
          hint={`${past.length} earlier ${past.length === 1 ? "value" : "values"}`}
        >
          <PastValues past={past} onCopy={(v, l) => void copy(v, l)} onRestore={onRestore} />
        </Disclosure>
      )}

      <div className="stack">
        <button type="button" className="btn danger big" onClick={onDelete}>
          Delete this password
        </button>
      </div>
    </>
  );
}

/**
 * What an item used to hold, and the way back to it.
 *
 * The vault has kept superseded values per field since the CRDT was written,
 * and nothing ever showed them. Two things changed that: the KeePass import
 * now replays years of somebody's earlier passwords into the same place, and
 * the reason people look here at all is the reason they need the button — a
 * password was changed, the change turned out to be wrong, and the old one is
 * the thing they are trying to get back.
 *
 * Restoring writes the old value as a new one rather than rewinding anything.
 * The value being replaced goes into history in its turn, so the way back is
 * never a one-way door.
 */
function PastValues({
  past,
  onCopy,
  onRestore,
}: {
  past: PastValue[];
  onCopy: (value: string, label: string) => void;
  onRestore: (field: string, value: string) => void;
}) {
  const [shown, setShown] = useState<string | null>(null);
  return (
    <>
      <p className="hint">
        Keyweb keeps what a field held before you changed it, so a change you did not mean to
        make is not the end of it.
      </p>
      {past.map((value) => {
        const key = `${value.field}:${value.atMs}`;
        const open = shown === key;
        return (
          <label className="field" key={key}>
            <span>
              {value.label} — {whenChanged(value.atMs)}
            </span>
            <div className="box">
              {value.secret && !open ? (
                <span className="mono secret-value" aria-label={`${value.label}, hidden`}>
                  {"\u2022".repeat(Math.min(value.value.length, 24))}
                </span>
              ) : (
                <CodeText value={value.value} className="mono secret-value" />
              )}
              {value.secret && (
                <button
                  type="button"
                  className="iconbtn"
                  onClick={() => setShown(open ? null : key)}
                >
                  {open ? "Hide" : "Show"}
                </button>
              )}
              <button
                type="button"
                className="iconbtn"
                onClick={() => onCopy(value.value, value.label)}
              >
                Copy
              </button>
              <button
                type="button"
                className="iconbtn"
                onClick={() => onRestore(value.field, value.value)}
              >
                Put this back
              </button>
            </div>
          </label>
        );
      })}
    </>
  );
}

/**
 * When a value was replaced, in words rather than a timestamp.
 *
 * An imported KeePass history can reach back a decade, and "2019-03-04
 * 10:00:00Z" is not how anybody remembers changing their bank password.
 */
function whenChanged(atMs: number): string {
  if (atMs <= 0) return "changed at some point";
  const days = Math.floor((Date.now() - atMs) / 86_400_000);
  if (days < 0) return "changed just now";
  if (days === 0) return "changed today";
  if (days === 1) return "changed yesterday";
  if (days < 30) return `changed ${days} days ago`;
  if (days < 365) {
    const months = Math.floor(days / 30);
    return `changed ${months} month${months === 1 ? "" : "s"} ago`;
  }
  const years = Math.floor(days / 365);
  return `changed ${years} year${years === 1 ? "" : "s"} ago`;
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
  const label = fieldLabel(name);

  return (
    <label className="field">
      <span>{label}</span>
      <div className="box">
        {secret && !shown ? (
          <span className="mono secret-value" aria-label={`${label}, hidden`}>
            {"\u2022".repeat(Math.min(value.length, 24))}
          </span>
        ) : secret ? (
          // The same colouring the password gets when it is revealed. A backup
          // PIN is read off the screen and typed somewhere else, which is
          // exactly where a zero gets copied down as a letter O.
          <CodeText value={value} className="mono secret-value" />
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
      {secret && shown && <CodeLegend />}
    </label>
  );
}

/**
 * The files kept on a password.
 *
 * Images get a thumbnail, because a row of identical paperclips is useless for
 * telling one scan from another — and a scan is the thing people most often
 * keep here. Everything else says what it is and offers to be saved.
 */
function Files({
  item,
  state,
  onOpen,
  onAttach,
  onRemove,
  onError,
}: {
  item: ItemRecord;
  state: VaultState;
  onOpen: (blobId: string) => void;
  onAttach: (file: File) => Promise<void>;
  onRemove: (blobId: string) => Promise<void>;
  onError: (message: string) => void;
}) {
  const [busy, setBusy] = useState(false);
  const files = attachmentsOf(item);

  return (
    <>
      {files.length > 0 && (
        <label className="field">
          <span>Files</span>
          <div className="files">
            {files.map((file) => {
              const blob = state.items[file.blobId];
              const type = blob ? (itemField(blob, "type") ?? "") : "";
              const size = Number(blob ? (itemField(blob, "size") ?? "0") : "0");
              return (
                <div key={file.blobId} className="file-row">
                  <Thumbnail blob={blob} type={type} name={file.name} />
                  <span className="rowtext" style={{ flex: 1 }}>
                    <b>{file.name}</b>
                    <span>
                      {blob ? humanSize(size) : "Still arriving from your other device"}
                    </span>
                  </span>
                  <button
                    type="button"
                    className="iconbtn"
                    disabled={!blob}
                    onClick={() => onOpen(file.blobId)}
                  >
                    Open
                  </button>
                  <button
                    type="button"
                    className="iconbtn"
                    disabled={busy}
                    onClick={async () => {
                      setBusy(true);
                      try {
                        await onRemove(file.blobId);
                      } finally {
                        setBusy(false);
                      }
                    }}
                  >
                    Remove
                  </button>
                </div>
              );
            })}
          </div>
        </label>
      )}

      <label className="field">
        <span>{files.length > 0 ? "Attach another file" : "Attach a file"}</span>
        <div className="box">
          <input
            type="file"
            disabled={busy}
            onChange={async (event) => {
              const file = event.target.files?.[0];
              // Cleared straight away so picking the same file twice in a row
              // still fires a change.
              event.target.value = "";
              if (!file) return;
              setBusy(true);
              try {
                await onAttach(file);
              } catch (cause) {
                onError(
                  cause instanceof Error ? cause.message : "Keyweb couldn't read that file.",
                );
              } finally {
                setBusy(false);
              }
            }}
          />
        </div>
        <span className="hint">
          Scans, recovery-code sheets, key files. They are locked with everything else and go
          wherever this keyring goes.
        </span>
      </label>
    </>
  );
}

/** A small preview for an image, and nothing at all for anything else. */
function Thumbnail({
  blob,
  type,
  name,
}: {
  blob: ItemRecord | undefined;
  type: string;
  name: string;
}) {
  const [url, setUrl] = useState<string | null>(null);
  const data = blob ? itemField(blob, "secret:data") : undefined;
  const viewable = isViewableImage(type);

  useEffect(() => {
    if (!viewable || !data) return;
    const created = attachmentUrl(data, type);
    setUrl(created);
    return () => {
      revokeAttachmentUrl(created);
      setUrl(null);
    };
  }, [data, type, viewable]);

  if (url) return <img className="thumb" src={url} alt={name} />;
  return (
    <span className="avatar" aria-hidden="true">
      {name.toLowerCase().replace(/^.*\./, "").slice(0, 3).toUpperCase() || "FILE"}
    </span>
  );
}
