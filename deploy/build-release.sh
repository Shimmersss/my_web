#!/usr/bin/env bash
# Build a Linux deployment bundle for the Web project.
# Usage: ./deploy/build-release.sh

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

git_status_for_manifest() {
  git -C "$ROOT" status --porcelain --untracked-files=all -- . \
    ':(exclude).release/**' \
    ':(exclude)server-upload/**' \
    ':(exclude)outputs/**' 2>/dev/null || true
}

command -v mvn >/dev/null 2>&1 || { echo "mvn not found"; exit 1; }
command -v npm >/dev/null 2>&1 || { echo "npm not found"; exit 1; }

rm -rf "$PACKAGE_ROOT"
mkdir -p "$PACKAGE_ROOT/backen" "$PACKAGE_ROOT/front" "$PACKAGE_ROOT/deploy" "$NPM_CACHE_DIR"

info "Building backend jar..."
(cd "$BACKEND_DIR" && mvn -q -DskipTests package)

info "Building frontend static files..."
if [[ -f "$FRONTEND_DIR/package-lock.json" ]]; then
  (cd "$FRONTEND_DIR" && npm ci --cache "$NPM_CACHE_DIR" && VITE_API_BASE_URL=/api npm run build)
else
  (cd "$FRONTEND_DIR" && npm install --cache "$NPM_CACHE_DIR" && VITE_API_BASE_URL=/api npm run build)
fi

if grep -Rqs 'api\.example\.com' "$FRONTEND_DIR/dist"; then
  echo "Frontend build contains placeholder API host api.example.com"
  exit 1
fi

info "Collecting release files..."
cp "$BACKEND_DIR/target/backen-0.0.1-SNAPSHOT.jar" "$PACKAGE_ROOT/backen/backen.jar"
cp -R "$BACKEND_DIR/scripts" "$PACKAGE_ROOT/backen/scripts"
find "$PACKAGE_ROOT/backen/scripts" -type d -name node_modules -prune -exec rm -rf {} +
find "$PACKAGE_ROOT/backen/scripts" \( -type d -name __pycache__ -o -type d -name .pytest_cache \) -prune -exec rm -rf {} +
find "$PACKAGE_ROOT/backen/scripts" -type f \( -name '*.pyc' -o -name '*.pyo' \) -delete
cp -R "$FRONTEND_DIR/dist" "$PACKAGE_ROOT/front/dist"
cp "$ROOT/.env.local.example" "$PACKAGE_ROOT/web.env.example"
cp "$ROOT/deploy/install-linux.sh" "$PACKAGE_ROOT/install-linux.sh"
cp "$ROOT/deploy/nginx.conf.template" "$PACKAGE_ROOT/deploy/nginx.conf.template"
cp "$ROOT/deploy/web-backen.service.template" "$PACKAGE_ROOT/deploy/web-backen.service.template"
cp "$ROOT/deploy/README.md" "$PACKAGE_ROOT/README.md"

if [[ -f "$ROOT/MAINTENANCE.md" ]]; then
  cp "$ROOT/MAINTENANCE.md" "$PACKAGE_ROOT/MAINTENANCE.md"
fi
if [[ -f "$ROOT/AGENTS.md" ]]; then
  cp "$ROOT/AGENTS.md" "$PACKAGE_ROOT/AGENTS.md"
fi
if [[ -f "$ROOT/WORKLOG.md" ]]; then
  cp "$ROOT/WORKLOG.md" "$PACKAGE_ROOT/WORKLOG.md"
fi

{
  echo "version=$VERSION"
  echo "built_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "git_commit=$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"
  echo "git_branch=$(git -C "$ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
  CHANGED_PATHS="$(git_status_for_manifest)"
  if [[ -n "$CHANGED_PATHS" ]]; then
    echo "git_dirty=true"
    echo "changed_paths<<EOF"
    printf '%s\n' "$CHANGED_PATHS"
    echo "EOF"
  else
    echo "git_dirty=false"
  fi
} > "$PACKAGE_ROOT/release-manifest.txt"

if [[ -f "$ROOT/.run/github-projects.json" ]]; then
  mkdir -p "$PACKAGE_ROOT/.run"
  cp "$ROOT/.run/github-projects.json" "$PACKAGE_ROOT/.run/github-projects.json"
fi

chmod +x "$PACKAGE_ROOT/install-linux.sh"

info "Creating archive..."
(cd "$RELEASE_DIR" && tar -czf "$ARCHIVE" web-homepage)

ok "Release package created: $ARCHIVE"
ok "Upload it to your Linux server, then run: tar -xzf $(basename "$ARCHIVE") && cd web-homepage && sudo ./install-linux.sh"
