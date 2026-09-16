import {
  GoogleDriveFolderPicker,
  type GoogleDrivePickedFile,
} from "@keyneom/sync-kit/stores/google-drive/picker";
import type { Authorization } from "@keyneom/sync-kit/core";
import { authorizeGoogle, hasDriveAccess } from "./googleAuth";

export { hasDriveAccess };

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
  /** When Drive last saw the file change, so the newest is offered first. */
  modifiedAt: number | null;
};

export class PickerUnavailable extends Error {
  constructor(message = "Keyweb isn't set up to browse your Google Drive yet.") {
    super(message);
    this.name = "PickerUnavailable";
  }
}

async function authorize(): Promise<Authorization> {
  return authorizeGoogle(CLIENT_ID);
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
    modifiedAt: null,
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
 * The files this Google account has handed over.
 *
 * Not a list Keyweb keeps — a question asked of Drive. Under `drive.file`,
 * `files.list` returns exactly the files the app created or was granted, so the
 * grant *is* the shared state. Nothing has to be synced, merged, or re-picked:
 * a file handed over in a browser shows up on the phone, because the grant is
 * scoped to the Cloud project and the Google account rather than to a device.
 *
 * Keyweb's own vault file and folder carry an `appProperties` marker and are
 * filtered out here, so its backup never appears as something to import.
 */
export async function listImportableFiles(
  { interactive = true }: { interactive?: boolean } = {},
): Promise<ImportSource[]> {
  if (!PICKER_CONFIGURED) throw new PickerUnavailable();
  // Asked for on a timer or a mount rather than a tap, and there is no token
  // yet: answer "nothing" rather than opening a popup nobody asked for.
  if (!interactive && !hasDriveAccess()) return [];
  const authorization = await authorize();

  const params = new URLSearchParams({
    spaces: "drive",
    corpora: "user",
    q: "trashed = false",
    fields: "files(id,name,modifiedTime,appProperties)",
    pageSize: "200",
    supportsAllDrives: "true",
    includeItemsFromAllDrives: "true",
  });
  const response = await fetch(`https://www.googleapis.com/drive/v3/files?${params}`, {
    headers: { Authorization: `Bearer ${authorization.accessToken}` },
  });
  if (!response.ok) {
    throw new Error(`Google Drive couldn't list your files (${response.status}).`);
  }

  const body = (await response.json()) as {
    files?: { id: string; name?: string; modifiedTime?: string; appProperties?: Record<string, string> }[];
  };
  return (body.files ?? [])
    .filter((file) => !file.appProperties?.["keyweb"])
    .filter((file) => (file.name ?? "").toLowerCase().endsWith(".kdbx"))
    .map((file) => ({
      fileId: file.id,
      name: file.name ?? "KeePass file",
      modifiedAt: file.modifiedTime ? Date.parse(file.modifiedTime) : null,
    }))
    .sort((a, b) => (b.modifiedAt ?? 0) - (a.modifiedAt ?? 0));
}
