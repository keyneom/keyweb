import { useCallback, useEffect, useState } from "react";
import { AlertIcon, BackIcon, CheckIcon, ShieldIcon } from "../ui/icons";
import {
  readKeePass,
  suggestedKeyringName,
  WrongMasterPassword,
  type ImportPreview,
  type UngroupedDestination,
} from "../vault/keepass";
import {
  hasDriveAccess,
  listImportableFiles,
  pickDriveFiles,
  PICKER_CONFIGURED,
  readDriveFile,
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
  | { name: "preview"; preview: ImportPreview; label: string; fileId?: string }
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
  keyrings,
  onBack,
  onImport,
}: {
  keyrings: { id: string; name: string }[];
  onBack: () => void;
  onImport: (preview: ImportPreview, ungrouped: UngroupedDestination) => Promise<number>;
}) {
  const [stage, setStage] = useState<Stage>({ name: "choose" });
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [sources, setSources] = useState<ImportSource[]>([]);
  const [listed, setListed] = useState(false);
  /**
   * Where the entries that are in no group should go.
   *
   * Null until the preview names a suggestion, so the suggested keyring name
   * can follow the file that was actually opened rather than being seeded from
   * whatever was opened first.
   */
  const [ungrouped, setUngrouped] = useState<UngroupedDestination | null>(null);

  /**
   * Ask Drive what this account has handed over.
   *
   * Under `drive.file` the answer is exactly the granted files, so the list is
   * the same in a browser and on a phone with no syncing at all. Failing is not
   * an error worth shouting about -- being signed out simply means there is
   * nothing to offer yet, and the picker below still works.
   */
  const refresh = useCallback(async ({ interactive = true } = {}) => {
    if (!PICKER_CONFIGURED) return;
    try {
      setSources(await listImportableFiles({ interactive }));
    } catch {
      setSources([]);
    } finally {
      setListed(true);
    }
  }, []);

  // Non-interactive on mount. Opening this screen is not consent to a Google
  // sign-in popup, and a popup not tied to a tap is blocked regardless.
  useEffect(() => {
    void refresh({ interactive: false });
  }, [refresh]);

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
      // Re-ask Drive rather than trusting what came back, so the list stays
      // the same question both platforms ask.
      await refresh();
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
      setUngrouped(
        preview.ungrouped === 0 ? null : { kind: "new", name: suggestedKeyringName(stage.label) },
      );
      setStage({
        name: "preview",
        preview,
        label: stage.label,
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
    const destination = ungrouped ?? { kind: "new" as const, name: suggestedKeyringName(stage.label) };
    if (stage.preview.ungrouped > 0 && destination.kind === "new" && !destination.name.trim()) {
      setError("Give the new keyring a name, or choose one you already have.");
      return;
    }
    setBusy(true);
    try {
      const count = await onImport(stage.preview, destination);
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
              <h2 className="import-heading">KeePass files in your Google Drive</h2>
              <div className="list">
                {sources.map((source) => (
                  <button
                    key={source.fileId}
                    type="button"
                    className="row"
                    disabled={busy}
                    onClick={() => void openFromDrive(source)}
                  >
                    <span className="avatar">KP</span>
                    <span className="rowtext">
                      <b>{source.name}</b>
                      <span>
                        {source.modifiedAt
                          ? `Changed ${new Date(source.modifiedAt).toLocaleDateString()}`
                          : "In your Drive"}
                      </span>
                    </span>
                  </button>
                ))}
              </div>
              <p className="hint" style={{ margin: "0.5rem 0 1.25rem" }}>
                Keyweb reads these straight out of Drive, so you can bring in changes as often as
                you like. The same list appears on your phone — it comes from Google, not from this
                browser.
              </p>
            </>
          )}

          {/* Only claim the list is empty once Drive has actually been asked.
              Before signing in, "you haven't shown Keyweb any files" is a
              guess, and a discouraging one for someone who already picked a
              file on their phone. */}
          {listed && sources.length === 0 && PICKER_CONFIGURED && (
            <p className="screen-sub">
              {hasDriveAccess()
                ? "You haven't shown Keyweb any KeePass files yet. Choose one below and it will stay available here and on your phone."
                : "Keyweb will ask Google which files you've pointed it at. Choose one below to sign in and get started — anything you've already picked on your phone shows up here too."}
            </p>
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
          {stage.preview.ungrouped > 0 && ungrouped && (
            <div className="ungrouped">
              <h2 className="import-heading">
                {stage.preview.ungrouped} password{stage.preview.ungrouped === 1 ? "" : "s"} aren't
                in a folder
              </h2>
              <p className="hint" style={{ margin: "0 0 0.75rem" }}>
                In your KeePass file these sit loose at the top rather than inside a folder. Keyweb
                keeps every password in a keyring, so choose where these should go.
              </p>

              <label className="field">
                <span>Put them in</span>
                <div className="box">
                  <select
                    value={ungrouped.kind === "new" ? "__new__" : ungrouped.keyringId}
                    onChange={(event) =>
                      setUngrouped(
                        event.target.value === "__new__"
                          ? { kind: "new", name: suggestedKeyringName(stage.label) }
                          : { kind: "existing", keyringId: event.target.value },
                      )
                    }
                  >
                    <option value="__new__">A new keyring</option>
                    {keyrings.map((ring) => (
                      <option key={ring.id} value={ring.id}>
                        {ring.name}
                      </option>
                    ))}
                  </select>
                </div>
              </label>

              {ungrouped.kind === "new" && (
                <label className="field">
                  <span>Name the new keyring</span>
                  <div className="box">
                    <input
                      type="text"
                      value={ungrouped.name}
                      onChange={(event) => setUngrouped({ kind: "new", name: event.target.value })}
                    />
                  </div>
                  <span className="hint">Named after your file to start with. Change it if you like.</span>
                </label>
              )}
            </div>
          )}

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
