import type { SyncStatus } from "@keyweb/vault-core";
import { AlertIcon, CloudIcon, ClockIcon, ShieldIcon } from "./icons";

/**
 * Backup state as a sentence, never a coloured dot.
 *
 * The rule this component exists to hold: we only say "backed up" when every
 * saved edit is provably in a published revision. Anything weaker gets a
 * sentence that says what is true and what happens next.
 */
export function StatusLine({
  status,
  backupConfigured,
}: {
  status: SyncStatus;
  backupConfigured: boolean;
}) {
  if (!backupConfigured) {
    return (
      <p className="status" data-tone="calm">
        <CloudIcon />
        <span>
          <b>Saved on this device.</b>
          <em>Encrypted backup isn't set up yet, so nothing leaves this computer.</em>
        </span>
      </p>
    );
  }

  if (status.pending > 0) {
    const what = status.pending === 1 ? "1 change" : `${status.pending} changes`;
    return (
      <p className="status" data-tone="attn">
        <ClockIcon />
        <span>
          <b>Saved on this device. {what} still to back up.</b>
          <em>
            {status.lastError ?? "We'll back them up as soon as we can reach your backup."}
          </em>
        </span>
      </p>
    );
  }

  if (status.lastError) {
    return (
      <p className="status" data-tone="risk">
        <AlertIcon />
        <span>
          <b>Everything is saved here, but backup had a problem.</b>
          <em>{status.lastError}</em>
        </span>
      </p>
    );
  }

  if (status.lastPublishedAt === null) {
    return (
      <p className="status" data-tone="calm">
        <CloudIcon />
        <span>
          <b>Saved on this device.</b>
          <em>Nothing has been backed up yet.</em>
        </span>
      </p>
    );
  }

  return (
    <p className="status" data-tone="safe">
      <ShieldIcon />
      <span>
        <b>Saved here and backed up.</b>
        <em>Last checked {formatWhen(status.lastPublishedAt)}.</em>
      </span>
    </p>
  );
}

function formatWhen(iso: string): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return iso;
  const seconds = Math.max(0, Math.round((Date.now() - then) / 1000));
  if (seconds < 60) return "just now";
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} minute${minutes === 1 ? "" : "s"} ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} hour${hours === 1 ? "" : "s"} ago`;
  return new Date(iso).toLocaleDateString();
}
