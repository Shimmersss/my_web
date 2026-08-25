#!/usr/bin/env bash
set -euo pipefail

ANDROID_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$ANDROID_DIR/.." && pwd)"
CERT_FINGERPRINT_FILE="$ANDROID_DIR/internal-signing-cert.sha256"
EXPECTED_PACKAGE_NAME="help.shimmer.app"
EXPECTED_VERSION_CODE="2"
EXPECTED_VERSION_NAME="0.1.1-internal"
EXPECTED_MIN_SDK="21"
EXPECTED_TARGET_SDK="35"
OUTPUT_NAME="shimmer-internal-0.1.1.apk"

normalize_sha256_fingerprint() {
  local digest="$1"
  [[ "$digest" =~ ^[0-9A-Fa-f]{64}$ ]] || return 1
  printf '%s' "$digest" \
    | tr '[:lower:]' '[:upper:]' \
    | sed -E 's/(..)/\1:/g; s/:$//'
}

read_expected_fingerprint() {
  local fingerprint
  [[ -f "$CERT_FINGERPRINT_FILE" ]] || {
    echo "Signing certificate fingerprint file not found: $CERT_FINGERPRINT_FILE" >&2
    return 1
  }
  IFS= read -r fingerprint < "$CERT_FINGERPRINT_FILE"
  [[ "$fingerprint" =~ ^([0-9A-F]{2}:){31}[0-9A-F]{2}$ ]] || {
    echo "Signing certificate fingerprint file is malformed" >&2
    return 1
  }
  printf '%s' "$fingerprint"
}

verify_apk_signing() {
  local apk="$1"
  local apksigner="$2"
  local expected_fingerprint="$3"
  local verify_output signer_digests signer_count actual_fingerprint

  verify_output="$("$apksigner" verify --verbose --print-certs "$apk")" || {
    echo "APK signature verification failed" >&2
    return 1
  }
  signer_digests="$(printf '%s\n' "$verify_output" \
    | sed -nE 's/^Signer #[0-9]+ certificate SHA-256 digest: ([0-9A-Fa-f]{64})$/\1/p')"
  signer_count="$(printf '%s\n' "$signer_digests" | sed '/^$/d' | wc -l | tr -d '[:space:]')"
  [[ "$signer_count" == "1" ]] || {
    echo "Expected exactly one APK signer certificate, found $signer_count" >&2
    return 1
  }
  actual_fingerprint="$(normalize_sha256_fingerprint "$signer_digests")" || {
    echo "APK signer SHA-256 fingerprint is malformed" >&2
    return 1
  }
  [[ "$actual_fingerprint" == "$expected_fingerprint" ]] || {
    echo "APK signer SHA-256 fingerprint does not match the pinned internal certificate" >&2
    return 1
  }
}

verify_apk_metadata() {
  local apk="$1"
  local aapt="$2"
  local badging package_name version_code version_name min_sdk target_sdk

  badging="$("$aapt" dump badging "$apk")" || {
    echo "Unable to read APK metadata with aapt" >&2
    return 1
  }
  package_name="$(printf '%s\n' "$badging" | sed -nE "s/^package: name='([^']+)'.*/\1/p" | head -n 1)"
  version_code="$(printf '%s\n' "$badging" | sed -nE "s/^package: .*versionCode='([^']+)'.*/\1/p" | head -n 1)"
  version_name="$(printf '%s\n' "$badging" | sed -nE "s/^package: .*versionName='([^']+)'.*/\1/p" | head -n 1)"
  min_sdk="$(printf '%s\n' "$badging" | sed -nE "s/^sdkVersion:'([^']+)'$/\1/p" | head -n 1)"
  target_sdk="$(printf '%s\n' "$badging" | sed -nE "s/^targetSdkVersion:'([^']+)'$/\1/p" | head -n 1)"

  [[ "$package_name" == "$EXPECTED_PACKAGE_NAME" ]] || {
    echo "Unexpected APK package name: ${package_name:-missing}" >&2
    return 1
  }
  [[ "$version_code" == "$EXPECTED_VERSION_CODE" ]] || {
    echo "Unexpected APK versionCode: ${version_code:-missing}" >&2
    return 1
  }
  [[ "$version_name" == "$EXPECTED_VERSION_NAME" ]] || {
    echo "Unexpected APK versionName: ${version_name:-missing}" >&2
    return 1
  }
  [[ "$min_sdk" == "$EXPECTED_MIN_SDK" ]] || {
    echo "Unexpected APK minSdk: ${min_sdk:-missing}" >&2
    return 1
  }
  [[ "$target_sdk" == "$EXPECTED_TARGET_SDK" ]] || {
    echo "Unexpected APK targetSdk: ${target_sdk:-missing}" >&2
    return 1
  }
}

sha256_file() {
  local file="$1"
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$file" | awk '{print $1}'
  else
    sha256sum "$file" | awk '{print $1}'
  fi
}

publish_verified_apk() {
  local source_apk="$1"
  local output_apk="$2"
  local apksigner="$3"
  local aapt="$4"
  local expected_fingerprint="$5"
  local output_dir output_marker candidate marker_candidate artifact_sha256

  output_dir="$(dirname "$output_apk")"
  output_marker="$output_apk.verified.sha256"
  mkdir -p "$output_dir"

  # A missing marker makes a failed invocation unambiguous even when an older,
  # previously verified APK remains available for manual rollback.
  rm -f "$output_marker"
  candidate="$(mktemp "$output_dir/.${OUTPUT_NAME}.candidate.XXXXXX")"
  trap 'rm -f "${candidate:-}" "${marker_candidate:-}"' RETURN
  marker_candidate="$(mktemp "$output_dir/.${OUTPUT_NAME}.marker.XXXXXX")" || return 1

  cp "$source_apk" "$candidate" || return 1
  verify_apk_signing "$candidate" "$apksigner" "$expected_fingerprint" || return 1
  verify_apk_metadata "$candidate" "$aapt" || return 1
  artifact_sha256="$(sha256_file "$candidate")" || return 1
  printf '%s  %s\n' "$artifact_sha256" "$(basename "$output_apk")" > "$marker_candidate" || return 1

  mv -f "$candidate" "$output_apk" || return 1
  candidate=""
  mv -f "$marker_candidate" "$output_marker" || return 1
  marker_candidate=""
  trap - RETURN
}

main() {
  local signing_config source_apk output_dir output_apk output_marker
  local apksigner aapt expected_fingerprint

  output_dir="$PROJECT_ROOT/.release/android"
  output_apk="$output_dir/$OUTPUT_NAME"
  output_marker="$output_apk.verified.sha256"
  mkdir -p "$output_dir"
  # The marker describes only this invocation. A failed retry may leave the
  # previous verified APK for rollback, but never a stale success marker.
  rm -f "$output_marker"

  signing_config="${SHIMMER_ANDROID_SIGNING_CONFIG:-$HOME/.config/shimmer-android/internal-signing.env}"
  [[ -f "$signing_config" ]] || { echo "Internal signing config not found: $signing_config" >&2; exit 1; }
  [[ "$(stat -f '%Lp' "$signing_config")" == "600" ]] || {
    echo "Internal signing config must have mode 0600: $signing_config" >&2
    exit 1
  }

  # This trusted local file is outside the repository and contains the keystore path and password.
  # shellcheck disable=SC1090
  source "$signing_config"
  [[ -n "${SHIMMER_ANDROID_KEYSTORE:-}" ]] || { echo "SHIMMER_ANDROID_KEYSTORE is required" >&2; exit 1; }
  [[ -n "${SHIMMER_ANDROID_STORE_PASSWORD:-}" ]] || { echo "SHIMMER_ANDROID_STORE_PASSWORD is required" >&2; exit 1; }
  [[ -f "$SHIMMER_ANDROID_KEYSTORE" ]] || { echo "Internal keystore not found: $SHIMMER_ANDROID_KEYSTORE" >&2; exit 1; }

  export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
  export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
  export SHIMMER_ANDROID_KEYSTORE SHIMMER_ANDROID_STORE_PASSWORD

  cd "$ANDROID_DIR"
  ./gradlew --no-daemon clean assembleRelease

  source_apk="$ANDROID_DIR/app/build/outputs/apk/release/app-release.apk"
  [[ -f "$source_apk" ]] || { echo "Release APK was not produced: $source_apk" >&2; exit 1; }
  apksigner="$(find "$ANDROID_HOME/build-tools" -maxdepth 2 -name apksigner -type f | sort -V | tail -n 1)"
  aapt="$(find "$ANDROID_HOME/build-tools" -maxdepth 2 -name aapt -type f | sort -V | tail -n 1)"
  [[ -x "$apksigner" ]] || { echo "apksigner not found under $ANDROID_HOME/build-tools" >&2; exit 1; }
  [[ -x "$aapt" ]] || { echo "aapt not found under $ANDROID_HOME/build-tools" >&2; exit 1; }
  expected_fingerprint="$(read_expected_fingerprint)"

  publish_verified_apk "$source_apk" "$output_apk" "$apksigner" "$aapt" "$expected_fingerprint"
  echo "Verified internal APK: $output_apk"
  echo "Verification marker: $output_marker"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
