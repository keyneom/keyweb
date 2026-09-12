import { useState } from "react";
import { AlertIcon, BackIcon, CheckIcon, ShieldIcon } from "../ui/icons";
import {
  readKeePass,
  WrongMasterPassword,
  type ImportPreview,
} from "../vault/keepass";

type Stage =
  | { name: "choose" }
  | { name: "password"; file: File; bytes: ArrayBuffer }
  | { name: "preview"; preview: ImportPreview }
  | { name: "done"; count: number };

/**
 * Importing a KeePass or KeeWeb file.
 *
 * Nothing is written until the last step, and the master password is used once
 * and dropped — it is never stored, and the file is never uploaded anywhere.
 * The preview exists so nobody is asked to trust an irreversible bulk change
 * they haven't seen the shape of.
 */
export function Import({
  onBack,
  onImport,
}: {
  onBack: () => void;
  onImport: (preview: ImportPreview) => Promise<number>;
}) {
  const [stage, setStage] = useState<Stage>({ name: "choose" });
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function choose(file: File | undefined) {
    if (!file) return;
    setError(null);
    setStage({ name: "password", file, bytes: await file.arrayBuffer() });
  }

  async function open() {
    if (stage.name !== "password") return;
    setBusy(true);
    setError(null);
    try {
      const preview = await readKeePass(stage.bytes, password);
      // The password has done its job; don't keep it around.
      setPassword("");
      if (preview.entries.length === 0) {
        setError("That file opened, but there were no passwords in it.");
        return;
      }
      setStage({ name: "preview", preview });
    } catch (cause) {
      setError(
        cause instanceof WrongMasterPassword || cause instanceof Error
          ? cause.message
          : "Keyweb couldn't read that file.",
      );
    } finally {
      setBusy(false);
    }
  }

  async function commit() {
    if (stage.name !== "preview") return;
    setBusy(true);
    try {
      const count = await onImport(stage.preview);
      setStage({ name: "done", count });
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Keyweb couldn't finish the import.");
    } finally {
      setBusy(false);
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

      <h1 className="screen-title">Bring in your KeePass passwords</h1>

      {error && (
        <p className="status" data-tone="attn">
          <AlertIcon />
          <span>
            <b>{error}</b>
          </span>
        </p>
      )}

      {stage.name === "choose" && (
        <>
          <p className="screen-sub">
            Keyweb can read a KeePass or KeeWeb file — the kind ending in <code>.kdbx</code>. Your
            folders and tags come across with it.
          </p>
          <label className="field">
            <span>Choose your file</span>
            <div className="box">
              <input
                type="file"
                accept=".kdbx,application/octet-stream"
                onChange={(event) => void choose(event.target.files?.[0])}
              />
            </div>
            <span className="hint">
              If it's in Google Drive, download it to this device first, then pick it here.
            </span>
          </label>
          <p className="status" data-tone="calm">
            <ShieldIcon />
            <span>
              <b>The file never leaves this device.</b>
              <em>Keyweb opens it here, copies what's inside, and forgets the password.</em>
            </span>
          </p>
        </>
      )}

      {stage.name === "password" && (
        <>
          <p className="screen-sub">
            Reading <b>{stage.file.name}</b>. Enter the master password you use to open it.
          </p>
          <label className="field">
            <span>Master password</span>
            <div className="box">
              <input
                type="password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                autoFocus
                onKeyDown={(event) => {
                  if (event.key === "Enter") void open();
                }}
              />
            </div>
            <span className="hint">Used once to open the file. Keyweb doesn't keep it.</span>
          </label>
          <button
            type="button"
            className="btn pri big"
            disabled={busy || password.length === 0}
            onClick={() => void open()}
          >
            {busy ? "Opening…" : "Open the file"}
          </button>
        </>
      )}

      {stage.name === "preview" && (
        <>
          <p className="screen-sub">
            Found <b>{stage.preview.entries.length} passwords</b> in{" "}
            <b>{stage.preview.keyringNames.length} folders</b>. Nothing has been copied yet.
          </p>
          <div className="list">
            {stage.preview.keyringNames.map((name) => {
              const count = stage.preview.entries.filter((e) => e.keyringName === name).length;
              return (
                <div key={name} className="row" style={{ cursor: "default" }}>
                  <span className="avatar">{name.slice(0, 2).toUpperCase()}</span>
                  <span className="rowtext">
                    <b>{name}</b>
                    <span>
                      {count} password{count === 1 ? "" : "s"} · becomes a keyring
                    </span>
                  </span>
                </div>
              );
            })}
          </div>
          {stage.preview.skipped > 0 && (
            <p className="screen-sub" style={{ marginTop: "0.9rem" }}>
              {stage.preview.skipped} empty {stage.preview.skipped === 1 ? "entry was" : "entries were"}{" "}
              skipped, along with anything in your KeePass recycle bin.
            </p>
          )}
          <div className="sticky-actions">
            <button
              type="button"
              className="btn pri big"
              disabled={busy}
              onClick={() => void commit()}
            >
              {busy ? "Copying…" : `Copy these ${stage.preview.entries.length} passwords in`}
            </button>
          </div>
        </>
      )}

      {stage.name === "done" && (
        <>
          <p className="status" data-tone="safe">
            <CheckIcon />
            <span>
              <b>{stage.count} passwords are now in Keyweb.</b>
              <em>Your folders came across as keyrings. Your original file is untouched.</em>
            </span>
          </p>
          <p className="screen-sub">
            If you keep using KeePass for a while, you can import the same file again later —
            Keyweb will update what changed instead of making copies.
          </p>
          <button type="button" className="btn pri big" onClick={onBack}>
            See my passwords
          </button>
        </>
      )}
    </>
  );
}
