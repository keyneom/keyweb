#!/usr/bin/env bash
# Build a signed Keyweb release APK from a clean web bundle.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@17}"
: "${ANDROID_HOME:=/opt/homebrew/share/android-commandlinetools}"
export JAVA_HOME ANDROID_HOME

ASSETS="$ROOT/packages/android/app/src/main/assets/web"

echo "==> Verifying"
npm --prefix "$ROOT" test
npm --prefix "$ROOT" run typecheck

echo "==> Building web bundle"
npm --prefix "$ROOT" run build --workspace @keyweb/web

echo "==> Staging bundle into Android assets"
rm -rf "$ASSETS"
mkdir -p "$ASSETS"
cp -R "$ROOT/packages/web/dist/." "$ASSETS/"

echo "==> Assembling signed release"
cd "$ROOT/packages/android"
if [ ! -f keystore.properties ]; then
  echo "keystore.properties is missing; the APK would be unsigned." >&2
  exit 1
fi
gradle --no-daemon :app:assembleRelease

APK="$ROOT/packages/android/app/build/outputs/apk/release/app-release.apk"
shasum -a 256 "$APK" | tee "$APK.sha256"

echo "==> Verifying signature"
"$ANDROID_HOME"/build-tools/35.0.0/apksigner verify --print-certs "$APK" | head -8

echo
echo "Release APK: $APK"
