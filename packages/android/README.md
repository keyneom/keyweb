# Keyweb for Android

Hosts the Keyweb vault in a WebView. The bundled web build is served through
`WebViewAssetLoader` on `https://appassets.androidplatform.net` rather than
`file://`, because `file://` is not a secure context and the vault depends on
`crypto.randomUUID`, `crypto.subtle` and IndexedDB.

## Build a signed release

Signing reads a gitignored `keystore.properties` beside this file:

```properties
storeFile=keyweb-release.jks
storePassword=...
keyAlias=keyweb
keyPassword=...
```

Then, from this directory:

```sh
npm run build --workspace @keyweb/web            # from the repo root
rm -rf app/src/main/assets/web
mkdir -p app/src/main/assets/web
cp -R ../web/dist/. app/src/main/assets/web/
JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
gradle :app:assembleRelease
```

The APK lands at `app/build/outputs/apk/release/app-release.apk`.

`scripts/release-android.sh` at the repo root does all of the above and writes
a checksum next to the APK.

## Install to a connected device

```sh
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Not in this build

Android Autofill, Credential Manager passkeys, and encrypted Google Drive
backup. The vault is local-first and says so on screen; backup activates when
`VITE_GOOGLE_CLIENT_ID` is set for the web build.
