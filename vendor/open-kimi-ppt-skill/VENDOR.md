# Vendored open-kimi-ppt-skill runtime

- Upstream: `https://github.com/jinwyp/open-ppt-skill` (previously published as `Binaryify/open-kimi-ppt-skill`)
- Version: `1.3.0`
- Commit: `07eeaadcb04c32c9adb107eb5c8608e6be4e1008`
- License file: `LICENSE`
- Imported: `bin/`, `lib/`, `editor/`, `skills/open-kimi-ppt/`, themes, README and changelog files
- Excluded: `.git/`, `docs/`, `example/`, `tests/`, promotional screenshots and development-only files

`UPSTREAM_MANIFEST.sha256` records every imported upstream file before local overlays.
Do not edit upstream files in this directory. Website integration belongs in
`backen/scripts/ppt-codex/` and `front/scripts/pptd-editor-overlay/` so an
upgrade can replace the vendor directory and re-apply an explicit overlay.

The editor bundle contains reverse-engineered and patched Kimi/neo-ppt assets.
Deployment is enabled only under the repository owner's separately confirmed
authorization. Keep PPTX generation root-only until per-task container
isolation and restricted network egress are implemented and reviewed.
