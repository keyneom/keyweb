import {
  GoogleDriveFolderPicker,
  type GoogleDrivePickedFile,
} from "@keyneom/sync-kit/stores/google-drive/picker";
import type { SharingDatasetFileV1 } from "@keyneom/sync-kit/sharing";
import { authorizeGoogle } from "../googleAuth";
import { PICKER_CONFIGURED, PickerUnavailable } from "../importSource";

/**
 * The one step nobody can do for you.
 *
 * `drive.file` is a per-file grant, and Google only issues it when the person
 * themselves selects the file in Google's own Picker. The owner sharing the
 * file with your email address is a separate thing and is not enough: it puts
 * the file in your Drive, but Keyweb still cannot see it until you hand it
 * over. Two permissions, two people, and they are not interchangeable.
 *
 * So this is the one screen in the whole flow that asks for something, and the
 * reason the rest of the flow works so hard to be finished before it and after
 * it without asking again.
 */

export class MissingGrant extends Error {
  /** The files that still are not readable, so the screen can name them. */
  readonly missing: SharingDatasetFileV1[];

  constructor(missing: SharingDatasetFileV1[]) {
    super(
      missing.length === 1
        ? "One of the shared keyrings wasn't chosen. Open the picker again and select it."
        : `${missing.length} of the shared keyrings weren't chosen. Open the picker again and select them.`,
    );
    this.name = "MissingGrant";
    this.missing = missing;
  }
}

const API_KEY = import.meta.env["VITE_GOOGLE_API_KEY"] ?? "";
const PROJECT_NUMBER = import.meta.env["VITE_GOOGLE_CLOUD_PROJECT_NUMBER"] ?? "";

/**
 * Ask for the shared files by id, and check we actually got them.
 *
 * Checked rather than assumed, because the Picker reports what was *selected*,
 * not what was granted, and selecting the wrong file in a list of similar names
 * is an ordinary mistake. Without the check, a join would appear to succeed and
 * the keyring would simply never arrive, with nothing to point at.
 */
export async function grantSharedFiles(files: SharingDatasetFileV1[]): Promise<void> {
  if (files.length === 0) return;
  if (!PICKER_CONFIGURED) throw new PickerUnavailable();

  const wanted = new Set(files.map((file) => file.fileId));
  const picker = new GoogleDriveFolderPicker({
    developerKey: API_KEY,
    cloudProjectNumber: PROJECT_NUMBER,
    title: files.length === 1 ? "Choose the shared keyring" : "Choose the shared keyrings",
  });

  const picked = await picker.pickFiles(await authorizeGoogle(), {
    multiSelect: files.length > 1,
  });
  const granted = new Set(
    picked.map((file: GoogleDrivePickedFile) => file.fileId).filter((id) => wanted.has(id)),
  );

  const missing = files.filter((file) => !granted.has(file.fileId));
  if (missing.length > 0) throw new MissingGrant(missing);
}
