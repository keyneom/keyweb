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

## Not in this build

Encrypted Drive backup, keyring sharing, Android Autofill, and Credential
Manager passkey unlock. The vault is local-first and says so on screen rather
than implying it is backing anything up. Backup arrives via
`com.keyneom:sync-kit-android`, which supplies the Drive transport, passkey PRF
and background sync; see `docs/google-setup.md` for the Cloud configuration.
