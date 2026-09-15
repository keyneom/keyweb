import { useCallback, useEffect, useState } from "react";
import { AlertIcon, CheckIcon, KeyIcon, ShieldIcon } from "../ui/icons";
import {
  listImportableFiles,
  pickDriveFiles,
  PICKER_CONFIGURED,
  type ImportSource,
} from "../vault/importSource";

/**
 * Handing a Drive file to Keyweb on the phone.
 *
 * ## Why this is its own screen, outside the vault
 *
 * Android has no native UI for `drive.file`, so the phone sends people here to
 * run the Picker. What it needs back is a *grant*, and a grant is a fact
 * recorded at Google against a Cloud project and a Google account. It has
 * nothing to do with a vault — not this browser's, not the phone's.
 *
 * Routing the handoff through the unlock gate made that invisible. Someone who
 * had only ever used Keyweb on their phone arrived at a browser demanding they
 * unlock a vault that does not exist here, and the way past it was to create a
 * second, empty vault and write down a recovery phrase for it. Neither the
 * unlocking nor the phrase had anything to do with the file they came to point
 * at, and the phrase was actively harmful: it looks exactly like the one that
 * matters, and it protects nothing.
 *
 * So the grant runs on its own. Sign in to Google, pick the file, go back to
 * the phone. The vault is never opened, never created, and never mentioned.
 */
export function Grant({ onContinue }: { onContinue: () => void }) {
  const [granted, setGranted] = useState<ImportSource[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);

  /**
   * What this account has already handed over.
   *
   * Asked of Google rather than remembered here, which is the whole point: the
   * answer is the same in this browser and on the phone. Failing means signed
   * out, which is not worth an error — the button below signs in anyway.
   */
  const refresh = useCallback(async ({ interactive = true } = {}) => {
    try {
      setGranted(await listImportableFiles({ interactive }));
    } catch {
      setGranted([]);
    }
  }, []);

  // Non-interactive on arrival: without a token already in hand this answers
  // "nothing" instead of throwing an OAuth popup at a page nobody has touched
  // yet, which browsers block anyway.
  useEffect(() => {
    if (PICKER_CONFIGURED) void refresh({ interactive: false });
  }, [refresh]);

  async function pick() {
    setBusy(true);
    setError(null);
    try {
      const picked = await pickDriveFiles();
      // Cancelling is a decision, not a failure.
      if (picked.length === 0) return;
      await refresh();
      setDone(true);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Keyweb couldn't open your Drive.");
    } finally {
      setBusy(false);
    }
  }

  if (!PICKER_CONFIGURED) {
    return (
      <section className="unlock">
        <p className="status" data-tone="attn">
          <AlertIcon />
          <span>
            <b>This copy of Keyweb can't reach Google Drive.</b>
            <em>
              It was built without its Google settings, so there is no file chooser to open. You
              can still bring a KeePass file in from your phone's own storage.
            </em>
          </span>
        </p>
      </section>
    );
  }

  return (
    <section className="unlock">
      <span className="unlock-mark" aria-hidden="true">
        <KeyIcon />
      </span>

      <h1 className="unlock-title">Point Keyweb at your KeePass file</h1>
      <p className="unlock-sub">
        Your phone sent you here because Google's file chooser only runs in a browser. Pick your
        file below, then go back to Keyweb on your phone.
      </p>

      {error && (
        <p className="status" data-tone="attn">
          <AlertIcon />
          <span>
            <b>{error}</b>
          </span>
        </p>
      )}

      {done && (
        <p className="status" data-tone="safe">
          <CheckIcon />
          <span>
            <b>Done — your phone can see it now.</b>
            <em>
              Go back to Keyweb on your phone and tap "I've picked it — check again". You can close
              this tab.
            </em>
          </span>
        </p>
      )}

      {granted.length > 0 && (
        <>
          <h2 className="import-heading">Files your phone can already open</h2>
          <div className="list">
            {granted.map((source) => (
              <div key={source.fileId} className="row" style={{ cursor: "default" }}>
                <span className="avatar">KP</span>
                <span className="rowtext">
                  <b>{source.name}</b>
                  <span>
                    {source.modifiedAt
                      ? `Changed ${new Date(source.modifiedAt).toLocaleDateString()}`
                      : "In your Drive"}
                  </span>
                </span>
              </div>
            ))}
          </div>
        </>
      )}

      <button type="button" className="btn pri big" disabled={busy} onClick={() => void pick()}>
        {busy
          ? "Opening Google Drive…"
          : granted.length > 0
            ? "Pick another file"
            : "Choose a file from Google Drive"}
      </button>

      <p className="status" data-tone="calm">
        <ShieldIcon />
        <span>
          <b>Nothing is being unlocked here.</b>
          <em>
            This only tells Google which file Keyweb may read. Your passwords stay on your phone —
            this browser never sees them, and you don't need an account here.
          </em>
        </span>
      </p>

      <button type="button" className="btn sec" onClick={onContinue}>
        Use Keyweb in this browser instead
      </button>
    </section>
  );
}
