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

mock_apksigner() {
  if [[ "$MOCK_VERIFY_FAIL" == "1" ]]; then
    return 1
  fi
  printf 'Verifies\nSigner #1 certificate SHA-256 digest: %s\n' "$MOCK_DIGEST"
}

mock_aapt() {
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

if find "$(dirname "$output_apk")" -maxdepth 1 -name ".${OUTPUT_NAME}.*" | grep -q .; then
  echo 'Temporary release files were not cleaned up' >&2
  exit 1
fi

echo '[android-build-test] fingerprint and atomic publication gates verified'
