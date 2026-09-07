---
name: android-emulator
description: >
  Build, install, launch, and debug Android apps on the local Android SDK
  emulator via adb and shell tools. Use when the task involves starting an AVD,
  installing an APK, taking device screenshots, reading logcat, deep-link
  testing, or UI automation (tap/swipe/type) on an emulator or connected
  physical device. Covers this machine's Homebrew Android SDK and the
  shimmer-test AVD, plus the Web repo's GeckoView app (help.shimmer.app).
---

# Drive the Android emulator with adb

Everything is plain CLI: Android SDK tools + adb. No MCP server or IDE required.

## Environment (this Mac)

- SDK root: `/opt/homebrew/share/android-commandlinetools` (Homebrew android-commandlinetools)
  - `emulator/emulator`, `platform-tools/adb`, `cmdline-tools/latest/bin/{sdkmanager,avdmanager}`
  - `sdkmanager` wrapper: `/opt/homebrew/bin/sdkmanager`
- AVD: `shimmer-test` (Android 35 google_apis arm64) — the only AVD; listed by `emulator -list-avds`
- JDK: Homebrew openjdk@17. `/usr/libexec/java_home` fails on this machine; set explicitly when a build tool needs it:
  `export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`
- arm64 system images need the accepted license `android-sdk-arm-dbt-license` (already accepted here).
- Physical devices: any device with USB debugging works too — every command below takes `-s <serial>`; omit it when only one device is attached.

Set a shell alias up front so commands stay short:

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
```

## Preflight

```bash
adb version && emulator -list-avds && adb devices
```

If `adb` is missing, install via `sdkmanager "platform-tools"`. Do not reinstall the SDK when a command fails — read the error first.

## Start an emulator and wait for boot

```bash
emulator -avd shimmer-test -no-snapshot-save -no-boot-anim -gpu host &   # add -no-window for headless; screencap still works
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
adb devices   # serial looks like emulator-5554
```

- `-no-snapshot-save` boots clean and discards session state; drop it to keep quick-boot snapshots.
- First cold boot of a fresh AVD takes several minutes; subsequent boots are fast.
- Keep at most one emulator per workflow; always pass `-s emulator-5554` if `adb devices` lists more than one device.

## Build (Web repo)

```bash
cd android && ./gradlew assembleDebug          # quick build, appId suffix .debug
./build-internal.sh                            # signed internal release → .release/android/shimmer-internal-<ver>.apk
```

`build-internal.sh` runs signing/manifest gates and publishes atomically; a failed build must not overwrite the existing APK. Release metadata lives in `android/app/build.gradle` (`applicationId help.shimmer.app`, `versionCode`, `versionName`).

## Install / launch / stop

```bash
adb -s <serial> install -r <path.apk>                     # -r keeps app data on upgrade
adb -s <serial> shell monkey -p help.shimmer.app -c android.intent.category.LAUNCHER 1
adb -s <serial> shell am force-stop help.shimmer.app
adb -s <serial> shell am start -a android.intent.action.VIEW -d "https://shimmer.help/"  # deep link
```

## Screenshots and logs

```bash
adb -s <serial> exec-out screencap -p > shot.png          # PNG to stdout; verify non-empty
pid=$(adb -s <serial> shell pidof -s help.shimmer.app | tr -d '\r')
adb -s <serial> logcat -d -t 500 --pid="$pid"             # recent app logs
adb -s <serial> logcat -d -b crash                        # crash buffer first when the app died
```

Full upstream exceptions/stack traces are for local diagnosis only — never paste them into user-facing task metadata.

## UI automation

```bash
adb -s <serial> shell uiautomator dump /sdcard/ui.xml
adb -s <serial> shell cat /sdcard/ui.xml > ui.xml          # parse node text/content-desc/bounds
```

- `bounds="[x1,y1][x2,y2]"` → tap center: `input tap $(( (x1+x2)/2 )) $(( (y1+y2)/2 ))`
- Swipe: `input swipe x1 y1 x2 y2 duration_ms`; keys: `input keyevent 4` (BACK), `66` (ENTER); scroll: `input swipe` on the list area.
- Text: `input text` is ASCII-only; escape spaces as `%s`. For CJK input install ADBKeyboard (`adb install ADBKeyboard.apk && adb shell ime set com.android.adbkeyboard/.AdbIME`, then `am broadcast -a ADB_INPUT_TEXT --es msg '中文'`). Don't claim typed Chinese text succeeded via plain `input text`.
- In GeckoView/WebView content, dumped nodes may be generic; tap by coordinates computed from the dump and verify the result with a screenshot, not by node state alone.

## Known gotchas (shimmer app)

- Deep links `https://shimmer.help/…` / `https://www.shimmer.help/…` route through `LauncherActivity` with `singleTask`; on the emulator `onNewIntent` intermittently does not fire when the app is already open — the app logs `ShimmerLauncher` diagnostics for exactly this. Filter with `adb logcat -d -s ShimmerLauncher`.
- The app is a GeckoView container loading the live site; page-level regressions usually come from the web release, not the APK. Only re-version the APK when native code or the update manifest changes.
- After gradle/debug installs the appId is `help.shimmer.app.debug` — a different app from the release build; don't mix their data when testing upgrades.

## Hygiene

- Stop the emulator when done: `adb -s <serial> emu kill`.
- Never report device-level acceptance (boot, install, login, upload/download flows) without matching logcat/screenshot evidence from this session.
- Emulator runs on the local Mac; production sizing (2 cores / 4 GB) does not apply here, but avoid running several AVDs at once.
