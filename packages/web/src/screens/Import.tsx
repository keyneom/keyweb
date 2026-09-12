import { useState } from "react";
import { AlertIcon, BackIcon, CheckIcon, ShieldIcon } from "../ui/icons";
import {
  readKeePass,
  WrongMasterPassword,
  type ImportPreview,
} from "../vault/keepass";
import {
  forgetSource,
  markImported,
  mergeSources,
  pickDriveFiles,
  PICKER_CONFIGURED,
  readDriveFile,
  readSources,
  rememberSources,
  type ImportSource,
} from "../vault/importSource";

type Stage =
  | { name: "choose" }
  | {
      name: "password";
      label: string;
      bytes: ArrayBuffer;
      /** Set when the bytes came from Drive, so the source can be recorded. */
      fileId?: string;
    }
  | { name: "preview"; preview: ImportPreview; fileId?: string }
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
  const [sources, setSources] = useState<ImportSource[]>(readSources);

  async function choose(file: File | undefined) {
    if (!file) return;
    setError(null);
    setStage({ name: "password", label: file.name, bytes: await file.arrayBuffer() });
  }

  /**
   * Hand files over through the Google Picker.
   *
   * This is the grant, not just a file browser: `drive.file` cannot see a
   * `.kdbx` until the user has picked it, which is why downloading by hand was
   * the only route before. Cancelling returns nothing and is not an error.
   */
  async function pick() {
    setBusy(true);
    setError(null);
    try {
      const picked = await pickDriveFiles();
      if (picked.length === 0) return;
      const next = mergeSources(sources, picked);
      setSources(next);
      rememberSources(next);
      if (picked.length === 1) await openFromDrive(picked[0]!);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Keyweb couldn't open your Drive.");
    } finally {
      setBusy(false);
    }
  }

  async function openFromDrive(source: ImportSource) {
    setBusy(true);
    setError(null);
    try {
      const bytes = await readDriveFile(source.fileId);
      setStage({ name: "password", label: source.name, bytes, fileId: source.fileId });
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Keyweb couldn't read that file.");
    } finally {
      setBusy(false);
    }
  }

  function forget(fileId: string) {
    const next = forgetSource(sources, fileId);
    setSources(next);
    rememberSources(next);
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
      setStage({
        name: "preview",
        preview,
        ...(stage.fileId ? { fileId: stage.fileId } : {}),
      });
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
      if (stage.fileId) {
        const next = markImported(sources, stage.fileId);
        setSources(next);
        rememberSources(next);
      }
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

          {sources.length > 0 && (
            <>
              <h2 className="import-heading">Files you've used before</h2>
              <div className="list">
                {sources.map((source) => (
                  <div key={source.fileId} className="row" style={{ cursor: "default" }}>
                    <span className="avatar">KP</span>
                    <span className="rowtext">
                      <b>{source.name}</b>
                      <span>
                        {source.lastImportedAt
                          ? `Last brought in ${new Date(source.lastImportedAt).toLocaleDateString()}`
                          : "Not brought in yet"}
                      </span>
                    </span>
                    <button
                      type="button"
                      className="iconbtn"
                      disabled={busy}
                      onClick={() => void openFromDrive(source)}
                    >
                      Open
                    </button>
                    <button
                      type="button"
                      className="iconbtn"
                      onClick={() => forget(source.fileId)}
                      aria-label={`Forget ${source.name}`}
                    >
                      Forget
                    </button>
                  </div>
                ))}
              </div>
              <p className="hint" style={{ margin: "0.5rem 0 1.25rem" }}>
                Keyweb reads these straight out of Drive, so you can bring in changes whenever you
                like. Forgetting one only removes it from this list.
              </p>
            </>
          )}

          {PICKER_CONFIGURED && (
            <>
              <button
                type="button"
                className="btn pri big"
                disabled={busy}
                onClick={() => void pick()}
              >
                {busy ? "Opening Google Drive…" : "Choose a file from Google Drive"}
              </button>
              <p className="hint" style={{ margin: "0.5rem 0 1.25rem" }}>
                Google will ask which file to share. Keyweb can only ever see the files you pick.
              </p>
            </>
          )}

          <label className="field">
            <span>Or choose a file on this device</span>
            <div className="box">
              <input
                type="file"
                accept=".kdbx,application/octet-stream"
                onChange={(event) => void choose(event.target.files?.[0])}
              />
            </div>
          </label>

          <p className="status" data-tone="calm">
            <ShieldIcon />
            <span>
              <b>Keyweb only ever reads your KeePass file.</b>
              <em>
                It is opened, copied from, and left exactly as it was — Keyweb never writes to it or
                changes it.
              </em>
            </span>
          </p>
        </>
      )}

      {stage.name === "password" && (
        <>
          <p className="screen-sub">
            Reading <b>{stage.label}</b>. Enter the master password you use to open it.
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
