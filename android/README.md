# Shimmer Android TWA

This directory contains the internal Trusted Web Activity wrapper for `https://shimmer.help/`.

- Application ID: `help.shimmer.app`
- Version: `0.1.0-internal` (`versionCode` 1)
- Minimum SDK: 21
- Compile/target SDK: 35
- Signing alias: `shimmer-internal`

The signing keystore and password are intentionally stored outside the repository.
Copy `internal-signing.env.example` to
`~/.config/shimmer-android/internal-signing.env`, fill it in, and set its mode to
`0600`. The current internal certificate SHA-256 is
`EA:D8:A2:08:BE:A1:26:3A:C0:AC:5F:77:52:B6:D6:DE:EB:BE:10:6C:AD:75:64:4F:AE:B8:CC:2F:5D:14:6C:D3`.

Build the signed internal APK with:

```bash
./android/build-internal.sh
```

The APK is written to `.release/android/shimmer-internal-0.1.0.apk`.
