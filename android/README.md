# Shimmer Android TWA

This directory contains the internal Trusted Web Activity wrapper for `https://shimmer.help/`.

- Application ID: `help.shimmer.app`
- Version: `0.1.1-internal` (`versionCode` 2)
- Minimum SDK: 21
- Compile/target SDK: 35
- Signing alias: `shimmer-internal`

The signing keystore and password are intentionally stored outside the repository.
Copy `internal-signing.env.example` to
`~/.config/shimmer-android/internal-signing.env`, fill it in, and set its mode to
`0600`. The public SHA-256 certificate fingerprint is pinned in
`internal-signing-cert.sha256`; the build and PWA checks both enforce that the
APK and Digital Asset Links use that exact value.

Build the signed internal APK with:

```bash
./android/build-internal.sh
```

After all signature, certificate and package metadata checks pass, the APK is
atomically written to `.release/android/shimmer-internal-0.1.1.apk` alongside a
`.verified.sha256` success marker. A failed build removes the marker and never
overwrites the previous APK with an unverified candidate.

Run the release-gate regression tests without a keystore or signing operation:

```bash
./android/tests/build-internal-test.sh
```

On first launch, the app lists the installed browsers that support Trusted Web
Activities or Custom Tabs. The selected browser is reused until the app data is
cleared or that browser is uninstalled.
