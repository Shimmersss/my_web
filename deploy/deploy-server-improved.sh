#!/usr/bin/env bash
# Build, upload, install, and verify the current production server.
# Usage: ./deploy/deploy-server-improved.sh

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RELEASE_DIR="$ROOT/.release"
LOCAL_CONFIG="$ROOT/.deploy.local"

if [[ -f "$LOCAL_CONFIG" ]]; then
  # shellcheck disable=SC1090
  source "$LOCAL_CONFIG"
fi

DEPLOY_HOST="${DEPLOY_HOST:-115.28.129.221}"
DEPLOY_USER="${DEPLOY_USER:-admin}"
DEPLOY_PORT="${DEPLOY_PORT:-22}"
DEPLOY_IDENTITY_FILE="${DEPLOY_IDENTITY_FILE:-}"
REMOTE_DIR="${REMOTE_DIR:-/home/admin/web-homepage}"
REMOTE_UPLOAD_DIR="${REMOTE_UPLOAD_DIR:-/home/admin/.web-homepage-releases}"
CONFIG_DIR="${CONFIG_DIR:-/etc/web-homepage}"
SERVICE_NAME="${SERVICE_NAME:-web-backen}"
NGINX_SITE_NAME="${NGINX_SITE_NAME:-corporate-site}"
DOMAIN="${DOMAIN:-115.28.129.221}"
PUBLIC_URL="${PUBLIC_URL:-http://115.28.129.221/}"
RUN_TESTS="${RUN_TESTS:-1}"
REQUIRE_CLEAN="${REQUIRE_CLEAN:-0}"
DRY_RUN="${DRY_RUN:-0}"
BACKEND_HEALTH_URL="${BACKEND_HEALTH_URL:-http://127.0.0.1:8080/api/health}"
LOCAL_SITE_URL="${LOCAL_SITE_URL:-http://127.0.0.1/}"
VERIFY_TIMEOUT="${VERIFY_TIMEOUT:-120}"
VERIFY_INTERVAL="${VERIFY_INTERVAL:-3}"

if [[ -z "${JAVA_HOME:-}" && -x /opt/homebrew/opt/openjdk@17/bin/java ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17
  export PATH="$JAVA_HOME/bin:$PATH"
fi

info() { printf '\033[36m[i]\033[0m %s\n' "$*"; }
ok() { printf '\033[32m[✓]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[!]\033[0m %s\n' "$*"; }
die() { printf '\033[31m[x]\033[0m %s\n' "$*" >&2; exit 1; }

print_command() {
  printf '  '
  printf '%q ' "$@"
  printf '\n'
}

run() {
  if [[ "$DRY_RUN" == "1" ]]; then
    print_command "$@"
  else
    "$@"
  fi
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "$1 not found"
}

validate_simple_value() {
  local name="$1"
  local value="$2"
  [[ "$value" != *$'\n'* && "$value" != *$'\r'* ]] || die "$name contains a newline"
  [[ "$value" != *"'"* ]] || die "$name contains a single quote"
}

for command in git mvn npm ssh scp curl tar; do
  require_command "$command"
done

for pair in \
  "DEPLOY_HOST=$DEPLOY_HOST" \
  "DEPLOY_USER=$DEPLOY_USER" \
  "REMOTE_DIR=$REMOTE_DIR" \
  "REMOTE_UPLOAD_DIR=$REMOTE_UPLOAD_DIR" \
  "CONFIG_DIR=$CONFIG_DIR" \
  "SERVICE_NAME=$SERVICE_NAME" \
  "NGINX_SITE_NAME=$NGINX_SITE_NAME" \
  "DOMAIN=$DOMAIN"; do
  validate_simple_value "${pair%%=*}" "${pair#*=}"
done

[[ "$DEPLOY_PORT" =~ ^[0-9]+$ ]] || die "DEPLOY_PORT must be a number"
[[ "$RUN_TESTS" == "0" || "$RUN_TESTS" == "1" ]] || die "RUN_TESTS must be 0 or 1"
[[ "$REQUIRE_CLEAN" == "0" || "$REQUIRE_CLEAN" == "1" ]] || die "REQUIRE_CLEAN must be 0 or 1"
[[ "$DRY_RUN" == "0" || "$DRY_RUN" == "1" ]] || die "DRY_RUN must be 0 or 1"
[[ "$VERIFY_TIMEOUT" =~ ^[0-9]+$ ]] || die "VERIFY_TIMEOUT must be a number"
[[ "$VERIFY_INTERVAL" =~ ^[0-9]+$ ]] || die "VERIFY_INTERVAL must be a number"

TARGET="$DEPLOY_USER@$DEPLOY_HOST"
SSH_OPTIONS=(-p "$DEPLOY_PORT")
SCP_OPTIONS=(-P "$DEPLOY_PORT")

if [[ -n "$DEPLOY_IDENTITY_FILE" ]]; then
  [[ -f "$DEPLOY_IDENTITY_FILE" ]] || die "Private key not found: $DEPLOY_IDENTITY_FILE"
  SSH_OPTIONS+=(-i "$DEPLOY_IDENTITY_FILE")
  SCP_OPTIONS+=(-i "$DEPLOY_IDENTITY_FILE")
fi

SSH=(ssh "${SSH_OPTIONS[@]}" "$TARGET")
SSH_TTY=(ssh -t "${SSH_OPTIONS[@]}" "$TARGET")
SCP=(scp "${SCP_OPTIONS[@]}")

info "Deployment target: $TARGET:$REMOTE_DIR"
if [[ -n "$DEPLOY_IDENTITY_FILE" ]]; then
  info "SSH private key: $DEPLOY_IDENTITY_FILE"
fi
info "Server config will be preserved: $CONFIG_DIR/web.env"

if [[ -n "$(git -C "$ROOT" status --porcelain)" ]]; then
  if [[ "$REQUIRE_CLEAN" == "1" ]]; then
    die "Git worktree is not clean. Commit or stash changes, or run with REQUIRE_CLEAN=0."
  fi
  warn "Git worktree contains uncommitted changes; they will be included in the release."
fi

if [[ "$RUN_TESTS" == "1" ]]; then
  info "Running backend tests..."
  if [[ "$DRY_RUN" == "1" ]]; then
    print_command bash -lc "cd '$ROOT/backen' && mvn -q test"
  else
    (cd "$ROOT/backen" && mvn -q test)
  fi
fi

info "Building release archive..."
run "$ROOT/deploy/build-release.sh"

if [[ "$DRY_RUN" == "1" ]]; then
  ARCHIVE="$RELEASE_DIR/web-homepage-<timestamp>.tar.gz"
  ARCHIVE_NAME="web-homepage-<timestamp>.tar.gz"
  VERSION="<timestamp>"
else
  ARCHIVE="$(ls -t "$RELEASE_DIR"/web-homepage-*.tar.gz 2>/dev/null | head -n 1 || true)"
  [[ -n "$ARCHIVE" && -f "$ARCHIVE" ]] || die "Release archive was not created"
  ARCHIVE_NAME="$(basename "$ARCHIVE")"
  VERSION="${ARCHIVE_NAME#web-homepage-}"
  VERSION="${VERSION%.tar.gz}"
fi

REMOTE_ARCHIVE="$REMOTE_UPLOAD_DIR/$ARCHIVE_NAME"
REMOTE_STAGE="$REMOTE_UPLOAD_DIR/stage-$VERSION"
REMOTE_PACKAGE="$REMOTE_STAGE/web-homepage"
REMOTE_BACKUP="$REMOTE_UPLOAD_DIR/web-homepage-backup-$VERSION.tar.gz"

info "Preparing remote release directory..."
run "${SSH[@]}" "mkdir -p '$REMOTE_UPLOAD_DIR'"

info "Uploading $ARCHIVE_NAME ..."
run "${SCP[@]}" "$ARCHIVE" "$TARGET:$REMOTE_ARCHIVE"

info "Creating server backup and extracting release..."
run "${SSH[@]}" \
  "set -e; rm -rf '$REMOTE_STAGE'; mkdir -p '$REMOTE_STAGE'; if [ -d '$REMOTE_DIR' ]; then tar -czf '$REMOTE_BACKUP' -C '$(dirname "$REMOTE_DIR")' '$(basename "$REMOTE_DIR")'; fi; tar -xzf '$REMOTE_ARCHIVE' -C '$REMOTE_STAGE'"

info "Installing release. sudo may ask for the server password..."
run "${SSH_TTY[@]}" \
  "sudo env INSTALL_DIR='$REMOTE_DIR' CONFIG_DIR='$CONFIG_DIR' SERVICE_NAME='$SERVICE_NAME' NGINX_SITE_NAME='$NGINX_SITE_NAME' DOMAIN='$DOMAIN' bash '$REMOTE_PACKAGE/install-linux.sh'"

info "Verifying backend, Nginx, and local server response..."
run "${SSH[@]}" \
  "set -e; \
  systemctl is-active '$SERVICE_NAME.service' >/dev/null; \
  deadline=\$((\$(date +%s) + $VERIFY_TIMEOUT)); \
  until curl -fsS '$BACKEND_HEALTH_URL' >/dev/null; do \
    if [ \"\$(date +%s)\" -ge \"\$deadline\" ]; then \
      echo '[x] Backend health check timed out: $BACKEND_HEALTH_URL' >&2; \
      systemctl status '$SERVICE_NAME.service' -n 50 --no-pager >&2 || true; \
      journalctl -u '$SERVICE_NAME.service' -n 120 --no-pager >&2 || true; \
      exit 1; \
    fi; \
    sleep '$VERIFY_INTERVAL'; \
  done; \
  curl -fsSI '$LOCAL_SITE_URL' >/dev/null"

info "Verifying public response: $PUBLIC_URL"
run curl -fsSI "$PUBLIC_URL"

info "Cleaning extracted staging directory..."
run "${SSH[@]}" "rm -rf '$REMOTE_STAGE'"

if [[ "$DRY_RUN" == "1" ]]; then
  ok "Dry run completed. No files were uploaded or installed."
else
  ok "Deployment completed: $PUBLIC_URL"
fi
ok "Server backup: $REMOTE_BACKUP"
ok "Uploaded archive: $REMOTE_ARCHIVE"
