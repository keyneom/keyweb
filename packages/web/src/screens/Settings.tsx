import { useMemo, useState } from "react";
import type { BackupFile } from "../vault/drive";
import type { BackupFileV1 } from "../vault/backupFile";
import {
  csvOmissions,
  exportCsv,
  exportVault,
  type VaultState,
} from "@keyweb/vault-core";
import { BackIcon } from "../ui/icons";
import type { Appearance, TextSize } from "../vault/useDisplaySettings";
import { LOCK_AFTER_CHOICES, type LockAfter } from "../vault/idleLock";
import type { SharingApi } from "../vault/useVault";

/**
 * The six characters that name you to the people you share with.
 *
 * Behind a button rather than shown on arrival, because reading it needs the
 * passkey, and a settings screen that demands a fingerprint prompt the moment
 * it opens teaches people to dismiss prompts without reading them.
 *
 * It is worth having at all because the share links travel over ordinary chat.
 * The one thing an attacker on that channel can do is substitute their own key
 * for someone's, and two people reading six characters to each other is what
 * catches it.
 */
function SharingKey({ sharing }: { sharing: SharingApi }) {
  const [value, setValue] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  return (
    <fieldset style={{ border: 0, padding: 0, margin: "0 0 1.75rem" }}>
      <legend style={{ fontWeight: 650, fontSize: "0.95em", padding: 0, marginBottom: "0.15rem" }}>
        Your sharing key
      </legend>
      <p style={{ color: "var(--muted)", fontSize: "0.86em", margin: "0 0 0.7rem" }}>
        When somebody shares a keyring with you, this is how their Keyweb knows it is really you.
        It is the same on every device you sign in to with this Google account.
      </p>
      {value ? (
        <p className="status" data-tone="calm">
          <span>
            <b className="mono">{value}</b>
            <em>
              Read these out to the person you are sharing with. If what they see doesn't match,
              the link was tampered with on the way.
            </em>
          </span>
        </p>
      ) : (
        <button
          type="button"
          className="btn sec big"
          disabled={busy}
          onClick={async () => {
            setBusy(true);
            setError(null);
            try {
              setValue(await sharing.myFingerprint());
            } catch (cause) {
              setError(
                cause instanceof Error ? cause.message : "Keyweb couldn't read your sharing key.",
              );
            } finally {
              setBusy(false);
            }
          }}
        >
          {busy ? "Confirming it's you…" : "Show my sharing key"}
        </button>
      )}
      {error && (
        <p className="status" data-tone="attn">
          <span>
            <b>{error}</b>
          </span>
        </p>
      )}
    </fieldset>
  );
}

function Choice<T extends string>({
  label,
  hint,
  value,
  options,
  onChange,
}: {
  label: string;
  hint: string;
  value: T;
  options: { value: T; label: string }[];
  onChange: (next: T) => void;
}) {
  return (
    <fieldset style={{ border: 0, padding: 0, margin: "0 0 1.75rem" }}>
      <legend style={{ fontWeight: 650, fontSize: "0.95em", padding: 0, marginBottom: "0.15rem" }}>
        {label}
      </legend>
      <p style={{ color: "var(--muted)", fontSize: "0.86em", margin: "0 0 0.7rem" }}>{hint}</p>
      <div className="stack">
        {options.map((option) => (
          <button
            key={option.value}
            type="button"
            className={value === option.value ? "btn pri big" : "btn sec big"}
            aria-pressed={value === option.value}
            onClick={() => onChange(option.value)}
          >
            {option.label}
          </button>
        ))}
      </div>
    </fieldset>
  );
}

export function Settings({
  textSize,
  appearance,
  onTextSize,
  onAppearance,
  lockAfter,
  onLockAfter,
  onBack,
  onImport,
  onScanCodes,
  onLock,
  describeBackupFile,
  backupFiles,
  onRefreshBackupFiles,
  onChooseBackupFile,
  onDeleteBackupFile,
  onSaveBackup,
  state,
  sharing,
}: {
  textSize: TextSize;
  appearance: Appearance;
  onTextSize: (value: TextSize) => void;
  onAppearance: (value: Appearance) => void;
  onBack: () => void;
  onImport: () => void;
  onScanCodes: () => void;
  onLock: () => void;
  lockAfter: LockAfter;
  onLockAfter: (value: LockAfter) => void;
  /** One line describing the backup file, for comparing against the phone. */
  describeBackupFile: () => Promise<string>;
  backupFiles: BackupFile[];
  onRefreshBackupFiles: () => Promise<void>;
  onChooseBackupFile: (fileId: string) => Promise<void>;
  onDeleteBackupFile: (fileId: string) => Promise<void>;
  onSaveBackup: () => Promise<BackupFileV1>;
  /** The vault as it stands, so an export is built from what is on screen. */
  state: VaultState;
  /** Null when this build has no Google account and so cannot share at all. */
  sharing: SharingApi | null;
}) {
  return (
    <>
      <header className="topbar">
        <button type="button" className="iconbtn" onClick={onBack}>
          <BackIcon />
          Back
        </button>
      </header>

      <h1 className="screen-title">Settings</h1>
      <p className="screen-sub">These change straight away. You can come back and change them again.</p>

      {/*
        First, because it is the thing somebody comes here in a hurry to do —
        stepping away from a shared computer. Locking existed from the
        beginning and was reachable from nowhere: the only way to lock was to
        close the tab, which is not something a person does deliberately while
        someone is waiting to use the machine.
      */}
      <fieldset style={{ border: 0, padding: 0, margin: "0 0 1.75rem" }}>
        <legend style={{ fontWeight: 650, fontSize: "0.95em", padding: 0, marginBottom: "0.15rem" }}>
          Lock Keyweb
        </legend>
        <p style={{ color: "var(--muted)", fontSize: "0.86em", margin: "0 0 0.7rem" }}>
          Closes your passwords straight away. You'll need your face, fingerprint or PIN to open
          them again.
        </p>
        <button type="button" className="btn sec big" onClick={onLock}>
          Lock now
        </button>
      </fieldset>

      <Choice
        label="Lock by itself"
        hint="When nobody has touched Keyweb for this long, it locks, and opening it again needs your face, fingerprint or PIN."
        value={lockAfter}
        options={LOCK_AFTER_CHOICES}
        onChange={onLockAfter}
      />

      <Choice
        label="Text size"
        hint="Makes everything in Keyweb bigger, including the buttons."
        value={textSize}
        options={[
          { value: "normal", label: "Normal text" },
          { value: "large", label: "Larger text" },
        ]}
        onChange={onTextSize}
      />

      <fieldset style={{ border: 0, padding: 0, margin: "0 0 1.75rem" }}>
        <legend style={{ fontWeight: 650, fontSize: "0.95em", padding: 0, marginBottom: "0.15rem" }}>
          Coming from another password app
        </legend>
        <p style={{ color: "var(--muted)", fontSize: "0.86em", margin: "0 0 0.7rem" }}>
          Bring in a KeePass or KeeWeb file, keeping your folders and tags.
        </p>
        <div className="stack">
          <button type="button" className="btn sec big" onClick={onImport}>
            Import from KeePass or KeeWeb
          </button>
          <button type="button" className="btn sec big" onClick={onScanCodes}>
            Codes from another app
          </button>
        </div>
        <p style={{ color: "var(--muted)", fontSize: "0.86em", margin: "0.6rem 0 0" }}>
          The second one brings your six-digit codes over from Google Authenticator, so they live
          beside the passwords they belong to.
        </p>
      </fieldset>

      <BackupFiles
        files={backupFiles}
        onRefresh={onRefreshBackupFiles}
        onUse={onChooseBackupFile}
        onDelete={onDeleteBackupFile}
      />

      <BackupDiagnostic describe={describeBackupFile} />

      <ExportSection state={state} onSaveBackup={onSaveBackup} />

      {sharing && <SharingKey sharing={sharing} />}

      <Choice
        label="Light or dark"
        hint="Match device follows whatever your computer or phone is set to."
        value={appearance}
        options={[
          { value: "device", label: "Match my device" },
          { value: "light", label: "Always light" },
          { value: "dark", label: "Always dark" },
        ]}
        onChange={onAppearance}
      />
    </>
  );
}

/**
 * Taking a copy of everything out.
 *
 * The door has to swing both ways. The import was built on the promise that
 * somebody could bring their KeePass file in and delete the original, and
 * without this that promise reads "your data is yours as long as you keep
 * using Keyweb" — the Drive backup is a sealed envelope only Keyweb can open,
 * which is a safety net and not a way out.
 */
function ExportSection({
  state,
  onSaveBackup,
}: {
  state: VaultState;
  onSaveBackup: () => Promise<BackupFileV1>;
}) {
  // Built once per render of this screen rather than on the click, so the
  // counts below and the file that gets written come from the same pass: a
  // warning derived separately from the thing it warns about goes stale.
  const exported = useMemo(() => exportVault(state), [state]);
  const omissions = csvOmissions(exported);

  function save(kind: "json" | "csv") {
    const text = kind === "csv" ? exportCsv(exported) : JSON.stringify(exported, null, 2);
    const stamp = new Date().toISOString().slice(0, 10);
    download(
      text,
      `keyweb-${stamp}.${kind}`,
      kind === "csv" ? "text/csv;charset=utf-8" : "application/json",
    );
  }

  const losses = [
    omissions.files > 0 && `${omissions.files} attached file${omissions.files === 1 ? "" : "s"}`,
    omissions.customFields > 0 &&
      `${omissions.customFields} of your own field${omissions.customFields === 1 ? "" : "s"}`,
    omissions.history > 0 &&
      `${omissions.history} earlier value${omissions.history === 1 ? "" : "s"}`,
  ].filter((part): part is string => typeof part === "string");

  const [backupBusy, setBackupBusy] = useState(false);
  const [backupError, setBackupError] = useState<string | null>(null);

  async function saveBackup() {
    setBackupBusy(true);
    setBackupError(null);
    try {
      const file = await onSaveBackup();
      download(
        JSON.stringify(file),
        `keyweb-backup-${new Date().toISOString().slice(0, 10)}.json`,
        "application/json",
      );
    } catch (cause) {
      setBackupError(cause instanceof Error ? cause.message : "Keyweb couldn't make the backup.");
    } finally {
      setBackupBusy(false);
    }
  }

  return (
    <fieldset style={{ border: 0, padding: 0, margin: "0 0 1.75rem" }}>
      <legend style={{ fontWeight: 650, fontSize: "0.95em", padding: 0, marginBottom: "0.15rem" }}>
        Take a copy of everything
      </legend>

      {/*
        The locked one first, because it is the one to keep.

        Everything else Keyweb holds lives in your Google account — which covers
        losing a passkey and does not cover losing Google. This file carries the
        passwords and the lock your recovery code opens, so the file and the
        code on paper are enough on their own, with no network at all.
      */}
      <p style={{ color: "var(--muted)", fontSize: "0.86em", margin: "0 0 0.7rem" }}>
        A locked copy that opens with your recovery code — even if Google is down and you have
        no other device. Keep it somewhere that isn&rsquo;t your Google account.
      </p>
      {backupError && (
        <p className="status" data-tone="risk" style={{ margin: "0 0 0.7rem" }}>
          <span>
            <b>{backupError}</b>
          </span>
        </p>
      )}
      <div className="stack" style={{ marginBottom: "1.25rem" }}>
        <button
          type="button"
          className="btn pri big"
          disabled={backupBusy}
          onClick={() => void saveBackup()}
        >
          {backupBusy ? "Locking it…" : "Save a locked backup"}
        </button>
      </div>

      <p style={{ color: "var(--muted)", fontSize: "0.86em", margin: "0 0 0.7rem" }}>
        Or your passwords in a plain file you can read, print, or load into another password app.
      </p>
      {/*
        Before the buttons, not after the file exists. Somebody who decides
        this is a bad idea should be able to decide it while there is still
        nothing on disk.
      */}
      <p className="status" data-tone="attention" style={{ margin: "0 0 0.7rem" }}>
        <span>
          <b>This file is not locked.</b>
          <em>
            Anyone who opens it can read every password in it. Save it somewhere only you can
            reach, and delete it when you&rsquo;re done.
          </em>
        </span>
      </p>
      <div className="stack">
        <button type="button" className="btn sec big" onClick={() => save("json")}>
          Save everything (keeps files and history)
        </button>
        <button type="button" className="btn sec big" onClick={() => save("csv")}>
          Save for another password app
        </button>
      </div>
      <p style={{ color: "var(--muted)", fontSize: "0.86em", margin: "0.6rem 0 0" }}>
        {exported.items.length} password{exported.items.length === 1 ? "" : "s"} either way. The
        second file is the one other apps can read
        {losses.length > 0 ? `, and it leaves behind ${losses.join(", ")} — those only fit in the first.` : "."}
      </p>
    </fieldset>
  );
}

/**
 * What this browser is looking at, in one line.
 *
 * In the open rather than behind a gesture, because the moment somebody needs
 * it is the moment they are least willing to hunt. "Both devices say they are
 * synced and show different things" is unanswerable from either side alone and
 * obvious from the two read-outs side by side — which file each is on, which
 * copies it holds, when each was written. A Drive file id and two timestamps;
 * nothing secret.
 */
function BackupDiagnostic({ describe }: { describe: () => Promise<string> }) {
  const [line, setLine] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  return (
    <fieldset style={{ border: 0, padding: 0, margin: "0 0 1.75rem" }}>
      <legend style={{ fontWeight: 650, fontSize: "0.95em", padding: 0, marginBottom: "0.15rem" }}>
        If something looks wrong
      </legend>
      <p style={{ color: "var(--muted)", fontSize: "0.86em", margin: "0 0 0.7rem" }}>
        Shows which backup file this browser is using and when each copy in it was last written.
        Useful when this browser and your phone disagree.
      </p>
      <button
        type="button"
        className="btn sec big"
        disabled={busy}
        onClick={async () => {
          setBusy(true);
          setLine(await describe());
          setBusy(false);
        }}
      >
        {busy ? "Checking…" : "Check the backup file"}
      </button>
      {line && (
        <p className="mono" style={{ fontSize: "0.82em", marginTop: "0.6rem", userSelect: "all" }}>
          {line}
        </p>
      )}
    </fieldset>
  );
}

/**
 * Every Keyweb backup in this Google account, and what can be told about one
 * without opening it.
 *
 * This existed only as a refusal: when the account held two files the app
 * would not guess between them and offered the choice at that moment. Which
 * meant somebody who suspected they had a stray file — from a browser that set
 * itself up twice, or a restore that went sideways — could not look, and
 * certainly could not tidy up.
 *
 * Nothing here needs a key. The date is the envelope header, the size is the
 * file's own, and "your recovery code opens this" is the presence of a second
 * copy rather than a claim about the code. The size is the honest answer to
 * "is that one empty": a sealed empty vault is about a kilobyte, and a real
 * one is not.
 */
function BackupFiles({
  files,
  onRefresh,
  onUse,
  onDelete,
}: {
  files: BackupFile[];
  onRefresh: () => Promise<void>;
  onUse: (fileId: string) => Promise<void>;
  onDelete: (fileId: string) => Promise<void>;
}) {
  const [looked, setLooked] = useState(false);
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  return (
    <fieldset className="card">
      <legend>Backups in this Google account</legend>
      <p className="hint">
        Keyweb keeps one file. If there are more, one of them is being read by
        something and the rest are strays — usually from a setup that ran twice.
      </p>

      {error && (
        <p className="status" data-tone="risk">
          <span>
            <b>{error}</b>
          </span>
        </p>
      )}

      {!looked ? (
        <button
          type="button"
          className="btn sec"
          disabled={busy}
          onClick={async () => {
            setBusy(true);
            setError(null);
            try {
              await onRefresh();
              setLooked(true);
            } catch (cause) {
              setError(cause instanceof Error ? cause.message : "Keyweb couldn't look.");
            } finally {
              setBusy(false);
            }
          }}
        >
          {busy ? "Looking…" : "Show me what is there"}
        </button>
      ) : files.length === 0 ? (
        <p className="hint">No Keyweb backup in this account yet.</p>
      ) : (
        <div className="list">
          {files.map((file) => (
            <div key={file.fileId} className="row" style={{ cursor: "default" }}>
              <span className="rowtext">
                <b>
                  Last changed {whenChanged(file.updatedAt)}
                  {file.inUse ? " · in use" : ""}
                </b>
                <span>
                  {humanSize(file.bytes)} ·{" "}
                  {file.hasCodeCopy
                    ? "your recovery code opens it"
                    : "no recovery copy — only the device that made it"}{" "}
                  · {file.fileId.slice(-6)}
                </span>
              </span>
              {!file.inUse && (
                <>
                  <button
                    type="button"
                    className="iconbtn"
                    disabled={busy}
                    onClick={() => void onUse(file.fileId)}
                  >
                    Use this one
                  </button>
                  <button
                    type="button"
                    className="iconbtn"
                    disabled={busy}
                    onClick={() => setConfirming(file.fileId)}
                  >
                    Delete
                  </button>
                </>
              )}
            </div>
          ))}
        </div>
      )}

      {confirming !== null && (
        <p className="status" data-tone="attn">
          <span>
            <b>Delete that backup file?</b>
            <em>
              Keyweb will not be able to open it again. Google keeps deleted files in your
              Drive bin for thirty days, so this is undoable there and nowhere else.
            </em>
            <span style={{ display: "flex", gap: "0.5rem" }}>
              <button
                type="button"
                className="btn pri"
                disabled={busy}
                onClick={async () => {
                  setBusy(true);
                  setError(null);
                  try {
                    await onDelete(confirming);
                    setConfirming(null);
                  } catch (cause) {
                    setError(
                      cause instanceof Error ? cause.message : "Keyweb couldn't delete it.",
                    );
                  } finally {
                    setBusy(false);
                  }
                }}
              >
                Delete it
              </button>
              <button type="button" className="btn sec" onClick={() => setConfirming(null)}>
                Keep it
              </button>
            </span>
          </span>
        </p>
      )}
    </fieldset>
  );
}

/** A file size somebody can judge a vault by. */
function humanSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} bytes`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** When the vault inside it last changed, in words. */
function whenChanged(at: string | null): string {
  if (!at) return "at some point";
  const then = Date.parse(at);
  if (Number.isNaN(then)) return "at some point";
  const minutes = Math.floor((Date.now() - then) / 60_000);
  if (minutes < 2) return "just now";
  if (minutes < 60) return `${minutes} minutes ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} hour${hours === 1 ? "" : "s"} ago`;
  const days = Math.floor(hours / 24);
  return days === 1 ? "yesterday" : `${days} days ago`;
}

/** Hand a file to the browser to save. */
function download(text: string, name: string, type: string) {
  const blob = new Blob([text], { type });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  link.click();
  // The blob holds the contents in memory until released, and the download
  // has already been handed to the browser by this point.
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}
