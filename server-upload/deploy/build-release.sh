#!/usr/bin/env bash
# Build a Linux deployment bundle for the Web project.
# Usage:
#   ./deploy/build-release.sh
#   SKIP_FRONTEND_BUILD=1 ./deploy/build-release.sh   # reuse front/dist when npm registry is unavailable

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BACKEND_DIR="$ROOT/backen"
FRONTEND_DIR="$ROOT/front"
RELEASE_DIR="$ROOT/.release"
PACKAGE_ROOT="$RELEASE_DIR/web-homepage"
VERSION="$(date +%Y%m%d-%H%M%S)"
ARCHIVE="$RELEASE_DIR/web-homepage-$VERSION.tar.gz"
NPM_CACHE_DIR="$RELEASE_DIR/npm-cache"

info() { printf '\033[36m[i]\033[0m %s\n' "$*"; }
ok() { printf '\033[32m[✓]\033[0m %s\n' "$*"; }

command -v mvn >/dev/null 2>&1 || { echo "mvn not found"; exit 1; }
command -v npm >/dev/null 2>&1 || { echo "npm not found"; exit 1; }

rm -rf "$PACKAGE_ROOT"
mkdir -p "$PACKAGE_ROOT/backen" "$PACKAGE_ROOT/front" "$PACKAGE_ROOT/deploy" "$NPM_CACHE_DIR"

info "Building backend jar..."
(cd "$BACKEND_DIR" && mvn -q -DskipTests package)

info "Building frontend static files..."
if [[ "${SKIP_FRONTEND_BUILD:-0}" == "1" ]]; then
  if [[ ! -f "$FRONTEND_DIR/dist/index.html" ]]; then
    echo "front/dist does not exist. Run without SKIP_FRONTEND_BUILD after npm registry is available."
    exit 1
  fi
  info "Skipping frontend build and reusing front/dist."
else
  if [[ -f "$FRONTEND_DIR/package-lock.json" ]]; then
    (cd "$FRONTEND_DIR" && npm ci --cache "$NPM_CACHE_DIR" && npm run build)
  else
    (cd "$FRONTEND_DIR" && npm install --cache "$NPM_CACHE_DIR" && npm run build)
  fi
fi

info "Collecting release files..."
cp "$BACKEND_DIR/target/backen-0.0.1-SNAPSHOT.jar" "$PACKAGE_ROOT/backen/backen.jar"
cp -R "$BACKEND_DIR/scripts" "$PACKAGE_ROOT/backen/scripts"
cp -R "$FRONTEND_DIR/dist" "$PACKAGE_ROOT/front/dist"
cp "$ROOT/.env.local.example" "$PACKAGE_ROOT/web.env.example"
if [[ -f "$ROOT/server-upload/web.env" ]]; then
  cp "$ROOT/server-upload/web.env" "$PACKAGE_ROOT/web.env"
fi
cp "$ROOT/deploy/install-linux.sh" "$PACKAGE_ROOT/install-linux.sh"
cp "$ROOT/deploy/nginx.conf.template" "$PACKAGE_ROOT/deploy/nginx.conf.template"
cp "$ROOT/deploy/nginx-location-snippet.conf" "$PACKAGE_ROOT/deploy/nginx-location-snippet.conf"
cp "$ROOT/deploy/web-backen.service.template" "$PACKAGE_ROOT/deploy/web-backen.service.template"
cp "$ROOT/deploy/README.md" "$PACKAGE_ROOT/README.md"

if [[ -f "$ROOT/.run/github-projects.json" ]]; then
  mkdir -p "$PACKAGE_ROOT/.run"
  cp "$ROOT/.run/github-projects.json" "$PACKAGE_ROOT/.run/github-projects.json"
fi

chmod +x "$PACKAGE_ROOT/install-linux.sh"

info "Creating archive..."
(cd "$RELEASE_DIR" && tar -czf "$ARCHIVE" web-homepage)

ok "Release package created: $ARCHIVE"
ok "Upload it to your Linux server, then run: tar -xzf $(basename "$ARCHIVE") && cd web-homepage && sudo ./install-linux.sh"
