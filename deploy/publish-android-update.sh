#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_CONFIG="${DEPLOY_LOCAL_CONFIG:-$ROOT/.deploy.local}"
ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
APK="${1:-$ROOT/.release/android/shimmer-internal-0.3.7.apk}"
REMOTE_UPDATE_DIR="${ANDROID_UPDATE_REMOTE_DIR:-/home/admin/web-homepage/.run/android-updates}"
NOTES="${ANDROID_UPDATE_NOTES:-后台超过 5 分钟返回时自动刷新页面，并修复 App 恢复前台后偶发点击无响应。}"

die() { printf '[x] %s\n' "$*" >&2; exit 1; }
sha256_file() {
  if command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'
  else sha256sum "$1" | awk '{print $1}'; fi
}

if [[ -f "$LOCAL_CONFIG" ]]; then
  [[ "$(stat -f '%Lp' "$LOCAL_CONFIG" 2>/dev/null || stat -c '%a' "$LOCAL_CONFIG")" == "600" ]] \
    || die "Local deploy config must have mode 0600: $LOCAL_CONFIG"
  # shellcheck disable=SC1090
  source "$LOCAL_CONFIG"
fi

: "${DEPLOY_HOST:?DEPLOY_HOST is required}"
DEPLOY_USER="${DEPLOY_USER:-admin}"
DEPLOY_PORT="${DEPLOY_PORT:-22}"
PUBLIC_URL="${PUBLIC_URL:-https://shimmer.help/}"
[[ -f "$APK" ]] || die "Verified APK not found: $APK"
MARKER="$APK.verified.sha256"
[[ -f "$MARKER" ]] || die "APK verification marker not found: $MARKER"

APK_NAME="$(basename "$APK")"
[[ "$APK_NAME" =~ ^shimmer-internal-[0-9A-Za-z.-]{1,64}\.apk$ ]] || die "Unsafe APK filename"
ACTUAL_SHA256="$(sha256_file "$APK")"
MARKER_SHA256="$(awk 'NR == 1 {print $1}' "$MARKER")"
[[ "$ACTUAL_SHA256" == "$MARKER_SHA256" ]] || die "APK marker digest does not match the artifact"
SIZE_BYTES="$(stat -f '%z' "$APK" 2>/dev/null || stat -c '%s' "$APK")"
[[ "$SIZE_BYTES" =~ ^[0-9]+$ && "$SIZE_BYTES" -gt 0 && "$SIZE_BYTES" -le 268435456 ]] \
  || die "APK size is outside the allowed range"

AAPT="$(find "$ANDROID_HOME/build-tools" -maxdepth 2 -name aapt -type f | sort -V | tail -n 1)"
[[ -x "$AAPT" ]] || die "aapt not found under $ANDROID_HOME/build-tools"
BADGING="$($AAPT dump badging "$APK")"
PACKAGE_NAME="$(printf '%s\n' "$BADGING" | sed -nE "s/^package: name='([^']+)'.*/\1/p" | head -1)"
VERSION_CODE="$(printf '%s\n' "$BADGING" | sed -nE "s/^package: .*versionCode='([^']+)'.*/\1/p" | head -1)"
VERSION_NAME="$(printf '%s\n' "$BADGING" | sed -nE "s/^package: .*versionName='([^']+)'.*/\1/p" | head -1)"
[[ "$PACKAGE_NAME" == "help.shimmer.app" ]] || die "Unexpected package: $PACKAGE_NAME"
[[ "$VERSION_CODE" =~ ^[1-9][0-9]*$ ]] || die "Invalid versionCode"
[[ "$VERSION_NAME" =~ ^[0-9A-Za-z.-]{1,80}$ ]] || die "Invalid versionName"

TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/shimmer-android-publish.XXXXXX")"
trap 'rm -rf "$TEMP_DIR"' EXIT
MANIFEST="$TEMP_DIR/latest.json"
node - "$MANIFEST" "$VERSION_CODE" "$VERSION_NAME" "$APK_NAME" "$ACTUAL_SHA256" "$SIZE_BYTES" "$NOTES" <<'NODE'
const fs = require('node:fs')
const [file, versionCode, versionName, apkFile, sha256, sizeBytes, notes] = process.argv.slice(2)
if (notes.length > 500) throw new Error('Update notes exceed 500 characters')
fs.writeFileSync(file, JSON.stringify({
  protocolVersion: 1,
  packageName: 'help.shimmer.app',
  versionCode: Number(versionCode),
  versionName,
  apkFile,
  sha256,
  sizeBytes: Number(sizeBytes),
  notes
}) + '\n', { encoding: 'utf8', mode: 0o600 })
NODE

STAMP="$(date +%Y%m%d-%H%M%S)-$$"
REMOTE_APK_CANDIDATE="$REMOTE_UPDATE_DIR/.${APK_NAME}.${STAMP}.candidate"
REMOTE_MANIFEST_CANDIDATE="$REMOTE_UPDATE_DIR/.latest.json.${STAMP}.candidate"
ssh -p "$DEPLOY_PORT" "$DEPLOY_USER@$DEPLOY_HOST" "install -d -m 0755 '$REMOTE_UPDATE_DIR'"
scp -P "$DEPLOY_PORT" "$APK" "$DEPLOY_USER@$DEPLOY_HOST:$REMOTE_APK_CANDIDATE"
scp -P "$DEPLOY_PORT" "$MANIFEST" "$DEPLOY_USER@$DEPLOY_HOST:$REMOTE_MANIFEST_CANDIDATE"
ssh -p "$DEPLOY_PORT" "$DEPLOY_USER@$DEPLOY_HOST" bash -s -- \
  "$REMOTE_UPDATE_DIR" "$REMOTE_APK_CANDIDATE" "$REMOTE_MANIFEST_CANDIDATE" \
  "$APK_NAME" "$ACTUAL_SHA256" "$SIZE_BYTES" <<'REMOTE'
set -euo pipefail
update_dir="$1"; apk_candidate="$2"; manifest_candidate="$3"
apk_name="$4"; expected_sha="$5"; expected_size="$6"
actual_sha="$(sha256sum "$apk_candidate" | awk '{print $1}')"
actual_size="$(stat -c '%s' "$apk_candidate")"
[[ "$actual_sha" == "$expected_sha" && "$actual_size" == "$expected_size" ]]
chmod 0644 "$apk_candidate" "$manifest_candidate"
mv -f "$apk_candidate" "$update_dir/$apk_name"
mv -f "$manifest_candidate" "$update_dir/latest.json"
REMOTE

BASE_URL="${PUBLIC_URL%/}"
curl -fsS "$BASE_URL/api/app-update/latest" | grep -q "\"versionCode\":$VERSION_CODE" \
  || die "Published manifest did not pass the public endpoint check"
curl -fsSI "$BASE_URL/api/app-update/apk/$APK_NAME" | grep -Eqi '^content-type: application/vnd.android.package-archive' \
  || die "Published APK did not pass the public endpoint check"

printf '[ok] Published Android update %s (%s)\n' "$VERSION_NAME" "$ACTUAL_SHA256"
