#!/usr/bin/env bash
set -euo pipefail

ANDROID_DIR="$(cd "$(dirname "$0")/.." && pwd)"
# The sourced path is resolved from this test's absolute directory.
# shellcheck disable=SC1091
source "$ANDROID_DIR/build-internal.sh"

EXPECTED_FINGERPRINT="$(read_expected_fingerprint)"
EXPECTED_DIGEST="$(printf '%s' "$EXPECTED_FINGERPRINT" | tr -d ':' | tr '[:upper:]' '[:lower:]')"
MOCK_DIGEST="$EXPECTED_DIGEST"
MOCK_VERIFY_FAIL=0
MOCK_WEBVIEW_CONTRACT=1
MOCK_GECKO_PAYLOAD=1

verify_apk_gecko_payload() {
  [[ "$MOCK_GECKO_PAYLOAD" == "1" ]]
}

mock_apksigner() {
  if [[ "$MOCK_VERIFY_FAIL" == "1" ]]; then
    return 1
  fi
  printf 'Verifies\nSigner #1 certificate SHA-256 digest: %s\n' "$MOCK_DIGEST"
}

mock_aapt() {
  if [[ "${1:-}" == "dump" && "${2:-}" == "xmltree" ]]; then
    if [[ "$MOCK_WEBVIEW_CONTRACT" == "1" ]]; then
      printf '%s\n' \
        'A: android:name="help.shimmer.app.LauncherActivity"' \
        'A: android:name="android.permission.INTERNET"' \
        'A: android:name="android.permission.REQUEST_INSTALL_PACKAGES"' \
        'A: android:name="androidx.core.content.FileProvider"' \
        'A: android:grantUriPermissions=(type 0x12)0xffffffff' \
        'A: android:usesCleartextTraffic=(type 0x12)0x0' \
        'A: android:name="android.intent.action.VIEW"' \
        'A: android:name="android.intent.category.BROWSABLE"' \
        'A: android:scheme="https"' \
        'A: android:host="shimmer.help"' \
        'A: geckoview.engine="bundled"'
    else
      printf '%s\n' \
        'A: android:name="com.google.androidbrowserhelper.trusted.LauncherActivity"' \
        'A: android:name="android.permission.INTERNET"'
    fi
    return
  fi
  printf "package: name='%s' versionCode='%s' versionName='%s' compileSdkVersion='35'\n" \
    "$EXPECTED_PACKAGE_NAME" "$EXPECTED_VERSION_CODE" "$EXPECTED_VERSION_NAME"
  printf "sdkVersion:'%s'\ntargetSdkVersion:'%s'\n" "$EXPECTED_MIN_SDK" "$EXPECTED_TARGET_SDK"
}

assert_file_content() {
  local file="$1"
  local expected="$2"
  [[ "$(<"$file")" == "$expected" ]] || {
    echo "Unexpected content in $file" >&2
    exit 1
  }
}

test_root="$(mktemp -d "${TMPDIR:-/tmp}/shimmer-android-build-test.XXXXXX")"
trap 'rm -rf "$test_root"' EXIT
source_apk="$test_root/source.apk"
output_apk="$test_root/release/$OUTPUT_NAME"
mkdir -p "$(dirname "$output_apk")"

printf 'candidate-one' > "$source_apk"
publish_verified_apk "$source_apk" "$output_apk" mock_apksigner mock_aapt "$EXPECTED_FINGERPRINT"
assert_file_content "$output_apk" 'candidate-one'
[[ -f "$output_apk.verified.sha256" ]]
grep -q "  $OUTPUT_NAME$" "$output_apk.verified.sha256"

printf 'known-good' > "$output_apk"
printf 'old-marker' > "$output_apk.verified.sha256"
printf 'wrong-certificate' > "$source_apk"
MOCK_DIGEST="$(printf '00%.0s' {1..32})"
if publish_verified_apk "$source_apk" "$output_apk" mock_apksigner mock_aapt "$EXPECTED_FINGERPRINT"; then
  echo 'Wrong signer fingerprint unexpectedly passed' >&2
  exit 1
fi
assert_file_content "$output_apk" 'known-good'
[[ ! -e "$output_apk.verified.sha256" ]]

printf 'verification-failure' > "$source_apk"
MOCK_DIGEST="$EXPECTED_DIGEST"
MOCK_VERIFY_FAIL=1
if publish_verified_apk "$source_apk" "$output_apk" mock_apksigner mock_aapt "$EXPECTED_FINGERPRINT"; then
  echo 'Signature verification failure unexpectedly passed' >&2
  exit 1
fi
assert_file_content "$output_apk" 'known-good'
[[ ! -e "$output_apk.verified.sha256" ]]

printf 'replacement' > "$source_apk"
MOCK_VERIFY_FAIL=0
publish_verified_apk "$source_apk" "$output_apk" mock_apksigner mock_aapt "$EXPECTED_FINGERPRINT"
assert_file_content "$output_apk" 'replacement'
[[ -f "$output_apk.verified.sha256" ]]

printf 'old-twa-contract' > "$source_apk"
MOCK_WEBVIEW_CONTRACT=0
if publish_verified_apk "$source_apk" "$output_apk" mock_apksigner mock_aapt "$EXPECTED_FINGERPRINT"; then
  echo 'Legacy browser-helper manifest unexpectedly passed' >&2
  exit 1
fi
assert_file_content "$output_apk" 'replacement'
[[ ! -e "$output_apk.verified.sha256" ]]
MOCK_WEBVIEW_CONTRACT=1

printf 'missing-gecko-runtime' > "$source_apk"
MOCK_GECKO_PAYLOAD=0
if publish_verified_apk "$source_apk" "$output_apk" mock_apksigner mock_aapt "$EXPECTED_FINGERPRINT"; then
  echo 'APK without bundled Gecko runtime unexpectedly passed' >&2
  exit 1
fi
assert_file_content "$output_apk" 'replacement'
[[ ! -e "$output_apk.verified.sha256" ]]
MOCK_GECKO_PAYLOAD=1

if find "$(dirname "$output_apk")" -maxdepth 1 -name ".${OUTPUT_NAME}.*" | grep -q .; then
  echo 'Temporary release files were not cleaned up' >&2
  exit 1
fi

launcher_source="$ANDROID_DIR/app/src/main/java/help/shimmer/app/LauncherActivity.java"
updater_source="$ANDROID_DIR/app/src/main/java/help/shimmer/app/AppUpdateManager.java"
grep -q 'BACKGROUND_REFRESH_AFTER_MS = 5L \* 60L \* 1000L' "$launcher_source" \
  || { echo 'Background refresh threshold is missing' >&2; exit 1; }
grep -q 'session.reload(GeckoSession.LOAD_FLAGS_BYPASS_CACHE)' "$launcher_source" \
  || { echo 'Extended-background refresh does not bypass stale cache' >&2; exit 1; }
grep -q 'session.setFocused(true)' "$launcher_source" \
  || { echo 'Foreground GeckoSession focus restoration is missing' >&2; exit 1; }
grep -q 'geckoView.requestFocus()' "$launcher_source" \
  || { echo 'Foreground GeckoView focus restoration is missing' >&2; exit 1; }
sed -n '/protected void onNewIntent/,/^    }/p' "$launcher_source" \
  | grep -q 'appUpdateManager.checkForUpdates()' || {
    echo 'A reused singleTask Activity does not recheck for updates' >&2
    exit 1
  }
grep -q 'setNegativeButton("取消"' "$updater_source" || {
  echo 'The optional update prompt is missing its cancel action' >&2
  exit 1
}
if grep -Eq 'SharedPreferences|getSharedPreferences|skippedVersion|ignoredVersion' "$updater_source"; then
  echo 'Update cancellation must not persist a skipped version' >&2
  exit 1
fi

echo '[android-build-test] fingerprint, atomic publication, and repeated update prompt gates verified'
