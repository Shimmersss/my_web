#!/usr/bin/env bash
set -euo pipefail

ANDROID_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$ANDROID_DIR/.." && pwd)"
SIGNING_CONFIG="${SHIMMER_ANDROID_SIGNING_CONFIG:-$HOME/.config/shimmer-android/internal-signing.env}"

[[ -f "$SIGNING_CONFIG" ]] || { echo "Internal signing config not found: $SIGNING_CONFIG" >&2; exit 1; }
[[ "$(stat -f '%Lp' "$SIGNING_CONFIG")" == "600" ]] || {
  echo "Internal signing config must have mode 0600: $SIGNING_CONFIG" >&2
  exit 1
}

# This file is outside the repository and contains the keystore path and password.
# shellcheck disable=SC1090
source "$SIGNING_CONFIG"
[[ -n "${SHIMMER_ANDROID_KEYSTORE:-}" ]] || { echo "SHIMMER_ANDROID_KEYSTORE is required" >&2; exit 1; }
[[ -n "${SHIMMER_ANDROID_STORE_PASSWORD:-}" ]] || { echo "SHIMMER_ANDROID_STORE_PASSWORD is required" >&2; exit 1; }
[[ -f "$SHIMMER_ANDROID_KEYSTORE" ]] || { echo "Internal keystore not found: $SHIMMER_ANDROID_KEYSTORE" >&2; exit 1; }

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export SHIMMER_ANDROID_KEYSTORE SHIMMER_ANDROID_STORE_PASSWORD

cd "$ANDROID_DIR"
./gradlew --no-daemon clean assembleRelease

SOURCE_APK="$ANDROID_DIR/app/build/outputs/apk/release/app-release.apk"
OUTPUT_DIR="$PROJECT_ROOT/.release/android"
OUTPUT_APK="$OUTPUT_DIR/shimmer-internal-0.1.0.apk"
mkdir -p "$OUTPUT_DIR"
cp "$SOURCE_APK" "$OUTPUT_APK"

APKSIGNER="$(find "$ANDROID_HOME/build-tools" -maxdepth 2 -name apksigner -type f | sort -V | tail -n 1)"
[[ -x "$APKSIGNER" ]] || { echo "apksigner not found under $ANDROID_HOME/build-tools" >&2; exit 1; }
"$APKSIGNER" verify --verbose --print-certs "$OUTPUT_APK"
echo "Internal APK: $OUTPUT_APK"
