# Keyweb for Android

A native Kotlin app: Compose UI, Room storage, and the vault semantics in
`:vault`.

## Modules

| Module | What it is |
| --- | --- |
| `:vault` | Pure Kotlin, no Android dependencies. The hybrid logical clock, the per-field CRDT merge, the operation log, and the sync engine. Tests run on the JVM in milliseconds. |
| `:app` | Compose UI, Room storage, and the Android entry point. |

`:vault` is a port of `packages/vault-core`, and the two must stay in step:
both platforms read the same encrypted Drive payload. `WireFormatTest` guards
that by parsing a fixture emitted from the TypeScript build and asserting
identical content *and* identical fingerprint. Regenerate the fixture with:

```sh
npm run fixture --workspace @keyweb/vault-core
```

## Why native rather than a WebView

The first build wrapped the web app in a WebView. That cannot work for the
product: Android Autofill needs a native `AutofillService`, Credential Manager
passkey PRF is a native API, background change detection is native, and Google
blocks OAuth inside embedded WebViews. Three of the four things that make this a
password manager rather than a website need native code.

## Build a signed release

Signing reads the environment first so CI can restore the keystore from GitHub
secrets, falling back to a gitignored `keystore.properties` beside this file:

```properties
storeFile=keyweb-release.jks
storePassword=...
keyAlias=keyweb
keyPassword=...
```

If neither source is complete the release build stays **unsigned** rather than
falling back to the debug key, which would ship an APK nobody could upgrade.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
./gradlew :vault:test :app:assembleRelease
```

The APK lands at `app/build/outputs/apk/release/app-release.apk`.
`scripts/release-android.sh` at the repo root runs the whole pipeline and
writes a checksum.

## Install to a connected device

```sh
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Sharing a keyring

Built, against the same Drive files and the same two links the web app uses —
see `docs/keyring-sharing.md`. The Drive half comes from
`com.keyneom:sync-kit-android`, which is published to **GitHub Packages** and
needs authentication even to read: set `gpr.user` and `gpr.key` in
`~/.gradle/gradle.properties`, or export `GITHUB_ACTOR` and `GITHUB_TOKEN`.
Without them the build fails at dependency resolution rather than at compile.

Two things about sharing on a phone specifically:

- **Granting a file leaves the app.** `drive.file` is a per-file grant and
  Google issues it only through the Picker, which runs only in a browser. The
  app hands the *file list* out to the web app's grant page and the person comes
  straight back; the vault stays here and does the joining itself.
- **Tapping a share link may not open Keyweb.** The app claims
  `keyneom.github.io/keyweb/`, but Android only honours that once an
  `assetlinks.json` naming it is served from the root of that domain — which is
  a different repository. Until then the keyrings screen takes a pasted link,
  which works on every phone regardless.

## Not in this build

Credential Manager passkey unlock. Keyweb answers password requests but is not
a passkey provider: issuing one needs a relying party to register it against,
and Keyweb has no server. See `docs/google-setup.md` for the Cloud
configuration behind backup and sharing.
