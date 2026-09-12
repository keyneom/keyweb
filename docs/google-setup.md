# Google Cloud setup for Keyweb

Encrypted backup and keyring sharing run entirely against Google Drive with no
Keyweb server. This records what is configured and, more usefully, *why* — the
scope choice in particular is easy to get wrong in a way that only shows up as
"my other phone can see the file but can't open it".

## Project

| | |
| --- | --- |
| Project name | KeyWeb |
| Project number | `955764773685` — Picker's `setAppId`, **not** the project id |
| Project id | `keyweb-507700` |

## APIs enabled

- **Google Drive API** — reading and writing the encrypted vault file.
- **Google Picker API** — the recipient's file-selection dialog. Separate from
  the Drive API and easy to miss; without it, sharing cannot complete because
  `drive.file` gives a recipient no access until they explicitly pick the file.

## Scopes — both are required

```
https://www.googleapis.com/auth/drive.file
https://www.googleapis.com/auth/drive.appdata
```

These do two different jobs, and dropping either breaks a different thing.

**`drive.file`** carries the vault data. Per-file access to files Keyweb created
or the user explicitly picked — it cannot see the rest of a user's Drive. Shared
keyrings live in normal, user-visible Drive under this scope because that is the
only place Google permits sharing a file with another person.

**`drive.appdata`** carries the *sharing identity*: the ECDH/ECDSA P-256 private
keys, passkey-encrypted, stored as `sync-kit-sharing-identity-<appId>.json` via
sync-kit's `DriveAppDataProtectedSharingIdentityStore`. This is how a **second
device belonging to the same person** obtains the key that decrypts the vault.
A lost private key cannot be reconstructed from the public backup, and the
identity is otherwise device-local in IndexedDB — so without this scope, signing
in on a new phone yields Drive access to ciphertext and no way to open it.

The app-data folder is right for this precisely because it is private to the app
and per-account. Google prohibits *sharing* app-data files with other people,
which is why vault data cannot live there — but a same-user cross-device secret
is exactly what it is for. easy-bc requests both scopes for the same reason; see
`web/src/sync/sharedSync.ts` (`GOOGLE_SCOPES`) and `web/src/sync/sharedIdentity.ts`.

Do **not** add the full `drive` scope. It is a restricted scope requiring a
Google security assessment, and it would grant reach a password vault should
never have.

## Consent screen

- Audience **External** (the only option without a Workspace organization).
- Starts in **Testing**: only listed test users can sign in at all, up to 100.
  Anyone not listed gets a hard "access blocked" error that reads like a bug in
  Keyweb rather than a console setting. Every family member used to test sharing
  must be added here first.

## Credentials

Neither of these is a secret — a browser app ships both to every visitor. What
protects the vault is that Drive only ever holds ciphertext, and that the
credentials are restricted to Keyweb's own origins.

- **OAuth 2.0 client id**, type *Web application* → `VITE_GOOGLE_WEB_CLIENT_ID`.
- **API key**, restricted to the Drive and Picker APIs → `VITE_GOOGLE_API_KEY`,
  used as Picker's `developerKey`.

In CI these are GitHub repository **variables**, not secrets, matching easy-bc:
`GOOGLE_WEB_CLIENT_ID`, `GOOGLE_API_KEY`, `GOOGLE_CLOUD_PROJECT_NUMBER`.

### Authorized JavaScript origins

An origin is `scheme://host[:port]` with **no path**, so the production entry is
the whole Pages origin:

```
https://keyneom.github.io      production (GitHub Pages)
http://localhost:5173          Vite dev server
http://localhost:4173          Vite preview
```

Note what that implies: `https://keyneom.github.io` covers *every* project site
on that account, not just `/keyweb/`. Any page served from that origin could
use this client id. easy-bc has the same property. A dedicated domain would
narrow it if that ever matters.

Android does not appear here, and does not need to. See below.

## Two OAuth clients, one project, one API key

This is the part that looks wrong until you see why it works.

| | Used by | Configured as |
| --- | --- | --- |
| **Web client** | The web app's sign-in, **and the Picker** | `VITE_GOOGLE_WEB_CLIENT_ID` |
| **Android client** | Native Drive calls from the app | Registered by package name + SHA-1 only; **never referenced in code** |
| **API key** | The Picker, and nothing else | `VITE_GOOGLE_API_KEY`, restricted to Websites |

**The Picker always runs in a browser, even for Android.** `drive.file` has no
native Android picker UI, so the app hands off to the web app's picker page.
easy-bc's own error copy says it plainly: *"Tap 'Grant folder access' to allow
it in your browser (sign in with this same Google account), then try joining
again."* That is why the API key carries a *Websites* restriction rather than an
Android one — an Android restriction would break the only place the key is used.

**A grant made in the browser reaches the Android client because `drive.file`
grants are scoped to the Cloud project, not to an individual OAuth client.**
That is exactly what Picker's `setAppId` expresses: it takes the **project
number**, not a client id. Same project, so a file picked under the web client
becomes visible to the Android client.

**The Android client is never named in code.** easy-bc's Android app calls
`Identity.getAuthorizationClient(activity).authorize(...)` from Play Services,
which identifies the app by its package name and signing certificate. The client
must exist in the project so Google can match it; no id string is ever compiled
in. That is why searching easy-bc's `android/` tree for a client id finds
nothing.

Keyweb's Android client will register `app.keyweb` with the release certificate
SHA-1 `12:E5:14:DA:EB:AD:D4:78:99:0A:FD:9A:95:D0:F3:20:60:DD:96:9F`.

One caveat worth carrying into testing: sync-kit lists live Google OAuth and
Picker validation as an open release gate, and easy-bc keeps a runtime probe
whose hypotheses include "grant never reached this OAuth client". So the
cross-client grant is the first thing to verify on a real device rather than
assume.

See `packages/web/.env.example`.
