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

- **OAuth 2.0 client id**, type *Web application* → `VITE_GOOGLE_CLIENT_ID`.
  Authorized JavaScript origins must list every origin the app is served from,
  including the Android WebView's `https://appassets.androidplatform.net`.
- **API key**, restricted to the Drive and Picker APIs → `VITE_GOOGLE_API_KEY`,
  used as Picker's `developerKey`.

See `packages/web/.env.example`.
