#!/usr/bin/env bash
# Upload the local release keystore to GitHub Actions secrets.
#
# Reads packages/android/keystore.properties and pipes each value straight into
# `gh secret set` over stdin, so no password is ever echoed to a terminal, a
# shell history, or a process argument list.
#
# Run once, after the repo has a GitHub remote:
#   ./scripts/set-android-secrets.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROPS="$ROOT/packages/android/keystore.properties"

[ -f "$PROPS" ] || { echo "Missing $PROPS" >&2; exit 1; }
command -v gh >/dev/null || { echo "The GitHub CLI (gh) is not installed." >&2; exit 1; }

prop() { sed -n "s/^$1=//p" "$PROPS"; }

STORE_FILE="$(prop storeFile)"
KEYSTORE="$ROOT/packages/android/$STORE_FILE"
[ -f "$KEYSTORE" ] || { echo "Missing keystore at $KEYSTORE" >&2; exit 1; }

echo "==> Uploading signing secrets to $(gh repo view --json nameWithOwner -q .nameWithOwner)"
base64 < "$KEYSTORE" | tr -d '\n' | gh secret set ANDROID_KEYSTORE_BASE64
prop storePassword | tr -d '\n' | gh secret set ANDROID_KEYSTORE_PASSWORD
prop keyAlias      | tr -d '\n' | gh secret set ANDROID_KEY_ALIAS
prop keyPassword   | tr -d '\n' | gh secret set ANDROID_KEY_PASSWORD

echo "==> Done. Secrets set:"
gh secret list | grep ANDROID_ || true
echo
echo "The keystore file itself still only exists on this machine."
echo "Keep an offline copy of $KEYSTORE and its password:"
echo "losing them means you can never ship an update to anyone who installed this build."
