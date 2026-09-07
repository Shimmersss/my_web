# Shimmer Android GeckoView App

This directory contains the first-party Android container for
`https://shimmer.help/`. Version `0.3.6-internal` embeds Mozilla GeckoView in
the APK. It does not render with Android System WebView, a Trusted Web
Activity, Custom Tabs, or an installed browser.

- Application ID: `help.shimmer.app`
- Version: `0.3.6-internal` (`versionCode` 11)
- Minimum SDK: 26; compile SDK: 36; target SDK: 35
- Release ABI: `arm64-v8a` (modern 64-bit ARM Android devices)
- Embedded engine: GeckoView `149.0.20260403140140`
- Trusted top-level hosts: `shimmer.help` and its canonicalized `www` hostname
- Native bridge origin: exactly `https://shimmer.help`

The signing keystore and password stay outside the repository. Copy
`internal-signing.env.example` to
`~/.config/shimmer-android/internal-signing.env`, fill it in, and set mode
`0600`. The public signing-certificate SHA-256 is pinned in
`internal-signing-cert.sha256`.

Build the signed internal APK with:

```bash
./android/build-internal.sh
```

The build atomically publishes
`.release/android/shimmer-internal-0.3.6.apk` plus its
`.verified.sha256` marker only after signature, certificate, package metadata,
SDK, App Link, release-hardening, and Gecko payload checks pass. The payload
gate requires the built-in bridge and `lib/arm64-v8a/libxul.so`, rejects a tiny
shell, and rejects duplicate non-release Gecko ABIs. Failed builds do not
replace the last verified APK.

Run the release-gate regression tests without signing:

```bash
./android/tests/build-internal-test.sh
```

## Runtime boundaries

- A process-wide `GeckoRuntime` and app-owned `GeckoSession` render the
  same-origin Vue application and retain their own Cookie/storage profile.
- Only `https://shimmer.help` remains in the session. `www` is canonicalized;
  other HTTPS, mail, and telephone links are passed to Android; unsupported
  schemes are denied. Cleartext traffic is disabled and TLS errors are never
  bypassed.
- A packaged WebExtension content script is limited to
  `https://shimmer.help/*`. Native messaging additionally verifies the current
  session, content-script environment, top-level sender, sender URL, current
  top-level URL, protocol version, and an allowlisted operation.
- Authenticated binary downloads are loaded by Gecko itself, so they use the
  Gecko session Cookie. The native side accepts only fixed same-origin API
  routes, caps responses at 256 MiB, caches privately, and hands the result to
  Android's document saver. Small text exports and uploads also use Android's
  Storage Access Framework; no broad storage permission is requested.
- The Vue app does not register its PWA Service Worker in this container. API,
  SSE, task, and download state remain server-authoritative.
- Gecko crashes or process kills reopen the owned session and expose the retry
  UI. Fullscreen, back navigation, App Links, file inputs, JavaScript alerts,
  confirm/prompt, and HTML select prompts have first-party handlers.

## Updates

The app reads `/api/app-update/latest`. A candidate is installable only when
its HTTPS URL, byte length, SHA-256, package name, increasing `versionCode`, and
single signing certificate match. Android still requires the user to approve
this app as an install source once and confirm each installation; updates are
not silent. An outdated build checks again on every explicit launcher or App
Link open. Cancelling dismisses the prompt for that opening only and is never
stored as a skipped version, so the next open offers the update again.

Publish an already verified APK and atomically switch the server manifest with:

```bash
./deploy/publish-android-update.sh
```

Update artifacts live in the server's persistent `.run/android-updates`
directory. `0.2.1-internal` already contains the updater and can offer 0.3.6;
`0.2.0-internal` must first be manually upgraded to 0.2.1 or newer.

The old TWA used a selected browser profile, and 0.2.x used Android System
WebView. GeckoView has a separate Cookie/storage profile, so users upgrading
from those older containers must sign in once; users upgrading from 0.3.4 or
newer keep the existing Gecko profile. Package name and signing certificate
are unchanged, so this remains an in-place APK update.

Do not claim device-level acceptance until a real arm64 device or licensed
emulator verifies cold launch without System WebView, sign-in persistence,
multi-file upload, SSE workflows, long PDF Canvas rendering, downloads,
PPTD/Reveal, deep links, rotation, process recovery, and upgrade from the
previously signed APK.
