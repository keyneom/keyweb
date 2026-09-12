import { GoogleWebAuthorizationProvider } from "@keyneom/sync-kit/auth/google-web";
import {
  GoogleDriveFolderPicker,
  type GoogleDrivePickedFile,
} from "@keyneom/sync-kit/stores/google-drive/picker";
import type { Authorization } from "@keyneom/sync-kit/core";
import { KEYWEB_SCOPES } from "./drive";

/**
 * Reaching a KeePass file that lives in Google Drive.
 *
 * ## Read-only, by construction
 *
 * This module can fetch bytes and nothing else. There is deliberately no write
 * or update method anywhere in it, so no future change can turn an import into
 * a modification by accident — the capability simply is not here to reach for.
 *
 * That distinction matters because the OAuth scope does **not** enforce it.
 * `drive.file` is read *and* write on every file the user picks, and Google
 * offers no per-file read-only grant through the Picker. So "Keyweb never
 * writes to your KeePass file" is a property of this code, not a promise the
 * platform is keeping on our behalf. Keeping the write capability out of the
 * type is how that stays true.
 *
 * ## Why the Picker at all
 *
 * `drive.file` can only see files the app created or the user explicitly handed
 * over through the Picker. Without it a `.kdbx` sitting in Drive is invisible,
 * and the only way in is downloading it by hand first. The Picker is the grant
 * mechanism, not just a file browser.
 *
 * The grant persists per file, which is what makes re-importing later a single
 * tap rather than another trip through Drive.
 */

const API_KEY = import.meta.env["VITE_GOOGLE_API_KEY"] ?? "";
const PROJECT_NUMBER = import.meta.env["VITE_GOOGLE_CLOUD_PROJECT_NUMBER"] ?? "";
const CLIENT_ID = import.meta.env["VITE_GOOGLE_WEB_CLIENT_ID"] ?? "";

export const PICKER_CONFIGURED = Boolean(API_KEY && PROJECT_NUMBER && CLIENT_ID);

/** A file the user has handed over, remembered so it can be re-read later. */
export type ImportSource = {
  fileId: string;
  name: string;
  /** Epoch millis of the last successful import, for ordering the list. */
  lastImportedAt: number | null;
};

export class PickerUnavailable extends Error {
  constructor(message = "Keyweb isn't set up to browse your Google Drive yet.") {
    super(message);
    this.name = "PickerUnavailable";
  }
}

async function authorize(): Promise<Authorization> {
  const provider = new GoogleWebAuthorizationProvider({
    clientId: CLIENT_ID,
    scope: KEYWEB_SCOPES,
  });
  return provider.authorize();
}

/**
 * Ask the user to hand over one or more files from their Drive.
 *
 * Returns an empty list when they cancel, which is not an error and must not be
 * reported as one.
 */
export async function pickDriveFiles(): Promise<ImportSource[]> {
  if (!PICKER_CONFIGURED) throw new PickerUnavailable();

  const picker = new GoogleDriveFolderPicker({
    developerKey: API_KEY,
    cloudProjectNumber: PROJECT_NUMBER,
    title: "Choose your KeePass file",
  });
  const picked = await picker.pickFiles(await authorize(), { multiSelect: true });
  return picked.map((file: GoogleDrivePickedFile) => ({
    fileId: file.fileId,
    name: file.name ?? "KeePass file",
    lastImportedAt: null,
  }));
}

/**
 * Fetch a file's bytes.
 *
 * `alt=media` returns the file content itself. This is the only Drive call the
 * import path makes, and it is a GET.
 */
export async function readDriveFile(fileId: string): Promise<ArrayBuffer> {
  if (!PICKER_CONFIGURED) throw new PickerUnavailable();
  const authorization = await authorize();
  const response = await fetch(
    `https://www.googleapis.com/drive/v3/files/${encodeURIComponent(fileId)}?alt=media&supportsAllDrives=true`,
    { headers: { Authorization: `Bearer ${authorization.accessToken}` } },
  );
  if (!response.ok) {
    throw new Error(
      response.status === 404
        ? "That file isn't in your Drive any more."
        : response.status === 403
          ? "Keyweb wasn't given permission to read that file. Choose it again."
          : `Google Drive couldn't send that file (${response.status}).`,
    );
  }
  return response.arrayBuffer();
}

/**
 * Files handed over previously, so re-importing is one tap.
 *
 * Kept in this browser rather than in the vault. A Drive file id is not secret,
 * but the list of files someone imports is mildly revealing and it is not worth
 * syncing something that would also have to be merged. The consequence — a
 * second device has to pick again — is stated in the UI rather than discovered.
 */
const SOURCES_KEY = "keyweb:import-sources";

export function readSources(): ImportSource[] {
  try {
    const stored = localStorage.getItem(SOURCES_KEY);
    const parsed = stored ? (JSON.parse(stored) as ImportSource[]) : [];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

export function rememberSources(sources: ImportSource[]): void {
  try {
    localStorage.setItem(SOURCES_KEY, JSON.stringify(sources));
  } catch {
    // Private browsing or full storage. The import still works; only the
    // shortcut for next time is lost.
  }
}

/** Merge newly picked files in, keeping any import history already recorded. */
export function mergeSources(
  existing: ImportSource[],
  picked: ImportSource[],
): ImportSource[] {
  const byId = new Map(existing.map((source) => [source.fileId, source]));
  for (const source of picked) {
    const previous = byId.get(source.fileId);
    byId.set(source.fileId, {
      ...source,
      lastImportedAt: previous?.lastImportedAt ?? null,
    });
  }
  return [...byId.values()].sort(
    (a, b) => (b.lastImportedAt ?? 0) - (a.lastImportedAt ?? 0),
  );
}

export function markImported(
  sources: ImportSource[],
  fileId: string,
  now = Date.now(),
): ImportSource[] {
  return sources
    .map((source) =>
      source.fileId === fileId ? { ...source, lastImportedAt: now } : source,
    )
    .sort((a, b) => (b.lastImportedAt ?? 0) - (a.lastImportedAt ?? 0));
}

export function forgetSource(sources: ImportSource[], fileId: string): ImportSource[] {
  // Only Keyweb's shortcut is dropped. The file stays in Drive, and the Drive
  // grant stays too -- revoking that is Google's screen, not ours to fake.
  return sources.filter((source) => source.fileId !== fileId);
}
