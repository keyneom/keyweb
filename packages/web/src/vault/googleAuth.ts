import { GoogleWebAuthorizationProvider } from "@keyneom/sync-kit/auth/google-web";
import {
  GOOGLE_DRIVE_APPDATA_SCOPE,
  GOOGLE_DRIVE_FILE_SCOPE,
} from "@keyneom/sync-kit/auth/google-web";
import type { Authorization } from "@keyneom/sync-kit/core";

/**
 * One Google authorization for the whole page.
 *
 * Every path to a token goes through a popup, and browsers only allow popups
 * during a user gesture. A provider per feature therefore does not merely
 * waste a round trip — the backup, the Picker, the import and the sharing
 * identity would each open their own popup, and the ones that did not arrive
 * on a tap would be blocked outright. Holding a single instance makes it one
 * sign-in that everything afterwards rides on.
 *
 * `drive.file` is what gives Keyweb its own private corner of Drive plus
 * whatever the person hands it through the Picker. `drive.appdata` is the
 * hidden per-account folder the sharing identity lives in; the reasoning for
 * both is in `docs/google-setup.md`.
 */

export const KEYWEB_SCOPES = `${GOOGLE_DRIVE_FILE_SCOPE} ${GOOGLE_DRIVE_APPDATA_SCOPE}`;

const CLIENT_ID = import.meta.env["VITE_GOOGLE_WEB_CLIENT_ID"] ?? "";

let provider: GoogleWebAuthorizationProvider | null = null;
let authorized = false;

/**
 * Whether a token has been obtained in this page session.
 *
 * The provider gives no way to ask, and the question matters: calling
 * `authorize()` speculatively — on mount, say — is not a silent no-op that
 * fails politely. It is a blocked popup, an error in the console, and on some
 * browsers a suppressed-popup bar the person has to deal with, all for
 * something they did not ask for.
 */
export function hasDriveAccess(): boolean {
  return authorized;
}

export function authorizeGoogle(clientId: string = CLIENT_ID): Promise<Authorization> {
  provider ??= new GoogleWebAuthorizationProvider({ clientId, scope: KEYWEB_SCOPES });
  return provider.authorize().then((authorization) => {
    authorized = true;
    return authorization;
  });
}

/** Drop the cached token at an account boundary. */
export function forgetGoogleAuthorization(): void {
  provider = null;
  authorized = false;
}

/**
 * Which Google account this is, for labelling a share.
 *
 * The sharing protocol is email-agnostic — grants are keyed to a public key,
 * never to an address — but a person choosing who to share with types an email
 * address, and the invitation has to say who it came from.
 */
export async function googleAccountEmail(): Promise<string | null> {
  const authorization = await authorizeGoogle();
  const response = await fetch("https://www.googleapis.com/oauth2/v3/userinfo", {
    headers: { Authorization: `Bearer ${authorization.accessToken}` },
  });
  if (!response.ok) return null;
  const body = (await response.json()) as { email?: unknown };
  return typeof body.email === "string" ? body.email : null;
}
