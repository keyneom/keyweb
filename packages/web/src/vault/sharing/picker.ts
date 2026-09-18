import {
  GoogleDriveFolderPicker,
  type GoogleDrivePickedFile,
} from "@keyneom/sync-kit/stores/google-drive/picker";
import { listAccessibleSyncKitDatasets } from "@keyneom/sync-kit/stores/google-drive/sharing";
import type { SharingDatasetFileV1 } from "@keyneom/sync-kit/sharing";
import { authorizeGoogle } from "../googleAuth";
import { KEYWEB_APP_ID } from "./controller";
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
 * Checked rather than assumed, because selecting the wrong file in a list of
 * similar names is an ordinary mistake. Without the check, a join would appear
 * to succeed and the keyring would simply never arrive, with nothing to point
 * at.
 *
 * The check asks *Drive*, not the Picker. This used to compare against the
 * Picker's own return value while the comment right here said that value
 * reports what was selected rather than what was granted — which is to say it
 * named the unreliable source and then trusted it anyway. `drive.file` grants
 * are the real state, `listAccessibleSyncKitDatasets` enumerates exactly what
 * the current grant covers, and asking the thing that knows costs one request.
 *
 * It also answers a question the Picker's return value cannot: a file granted
 * on *another device* signed into the same account is already accessible here,
 * because the grant follows the Cloud project and the account rather than the
 * browser. Somebody who granted on their phone should not be asked again.
 */
export async function grantSharedFiles(files: SharingDatasetFileV1[]): Promise<void> {
  if (files.length === 0) return;
  if (!PICKER_CONFIGURED) throw new PickerUnavailable();

  // Ask before opening anything: the grant may already cover these.
  const already = await accessibleFileIds();
  const outstanding = files.filter((file) => !already.has(file.fileId));
  if (outstanding.length === 0) return;

  const picker = new GoogleDriveFolderPicker({
    developerKey: API_KEY,
    cloudProjectNumber: PROJECT_NUMBER,
    title: outstanding.length === 1 ? "Choose the shared keyring" : "Choose the shared keyrings",
  });

  await picker.pickFiles(await authorizeGoogle(), { multiSelect: outstanding.length > 1 });

  const granted = await accessibleFileIds();
  const missing = files.filter((file) => !granted.has(file.fileId));
  if (missing.length > 0) throw new MissingGrant(missing);
}

/**
 * Every shared file this grant can actually reach, by file id.
 *
 * A failure here must not read as "nothing is granted", or a join that was
 * already complete would demand the Picker again and a join that just
 * succeeded would be reported as failed. Unknown is returned as unknown and
 * the caller falls back to asking.
 */
async function accessibleFileIds(): Promise<Set<string>> {
  try {
    const datasets = await listAccessibleSyncKitDatasets({
      appId: KEYWEB_APP_ID,
      authorization: await authorizeGoogle(),
    });
    return new Set(datasets.map((dataset) => dataset.fileId));
  } catch {
    return new Set();
  }
}
