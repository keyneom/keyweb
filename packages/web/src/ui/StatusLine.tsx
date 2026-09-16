import type { ReactElement } from "react";
import type { SyncStatus } from "@keyweb/vault-core";
import { AlertIcon, CloudIcon, ClockIcon, ShieldIcon } from "./icons";

/**
 * Backup state as a sentence, never a coloured dot.
 *
 * The rule this component exists to hold: we only say "backed up" when every
 * saved edit is provably in a published revision. Anything weaker gets a
 * sentence that says what is true and what happens next.
 *
 * It also carries the only way to make a backup happen on demand. Syncing is
 * automatic after every edit, but "automatic" is not the same as "visibly
 * finished" — and when the last attempt failed, or another device has changes
 * this one has not seen, waiting is the one thing a person cannot do anything
 * with. The button goes here rather than in Settings because this is where the
 * doubt is: it is the line that just said something was not backed up yet.
 */
export function StatusLine({
  status,
  backupConfigured,
  onSync,
  syncing = false,
}: {
  status: SyncStatus;
  backupConfigured: boolean;
  onSync?: () => void;
  /** A run already in flight, so the button cannot start a second. */
  syncing?: boolean;
}) {
  const view = describe(status, backupConfigured);

  return (
    <p className="status" data-tone={view.tone}>
      {view.icon}
      <span>
        <b>{view.headline}</b>
        <em>{view.detail}</em>
        {/* Offered only where it can do something: with backup off there is
            nowhere to sync to, and the set-up button is the real next step. */}
        {backupConfigured && onSync && (
          <button
            type="button"
            className="status-action"
            disabled={syncing}
            onClick={onSync}
          >
            {syncing ? "Checking…" : view.action}
          </button>
        )}
      </span>
    </p>
  );
}

type View = {
  tone: string;
  icon: ReactElement;
  headline: string;
  detail: string;
  /** Says what pressing it will do here, which differs by state. */
  action: string;
};

function describe(status: SyncStatus, backupConfigured: boolean): View {
  if (!backupConfigured) {
    return {
      tone: "calm",
      icon: <CloudIcon />,
      headline: "Saved on this device.",
      detail: "Encrypted backup isn't set up yet, so nothing leaves this computer.",
      action: "",
    };
  }

  if (status.pending > 0) {
    const what = status.pending === 1 ? "1 change" : `${status.pending} changes`;
    return {
      tone: "attn",
      icon: <ClockIcon />,
      headline: `Saved on this device. ${what} still to back up.`,
      detail: status.lastError ?? "We'll back them up as soon as we can reach your backup.",
      action: "Back up now",
    };
  }

  if (status.lastError) {
    return {
      tone: "risk",
      icon: <AlertIcon />,
      headline: "Everything is saved here, but backup had a problem.",
      detail: status.lastError,
      action: "Try again",
    };
  }

  if (status.lastPublishedAt === null) {
    return {
      tone: "calm",
      icon: <CloudIcon />,
      headline: "Saved on this device.",
      detail: "Nothing has been backed up yet.",
      action: "Back up now",
    };
  }

  return {
    tone: "safe",
    icon: <ShieldIcon />,
    headline: "Saved here and backed up.",
    detail: `Last checked ${formatWhen(status.lastPublishedAt)}.`,
    // Nothing is waiting to go up, but a check also brings down whatever
    // another device has published since.
    action: "Check now",
  };
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
