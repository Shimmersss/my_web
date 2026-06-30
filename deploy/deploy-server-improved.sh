#!/usr/bin/env bash
# Self-contained build, upload, install, and verify script for the Web project.
# Usage: ./deploy/deploy-server-improved.sh
#
# Local overrides are read from .deploy.local when present.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCAL_CONFIG="$ROOT/.deploy.local"
RELEASE_DIR="$ROOT/.release"
PACKAGE_ROOT="$RELEASE_DIR/web-homepage"
NPM_CACHE_DIR="$RELEASE_DIR/npm-cache"

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
DOMAIN="${DOMAIN:-shimmer.help}"
PUBLIC_URL="${PUBLIC_URL:-https://shimmer.help/}"
FORCE_NGINX_CONFIG="${FORCE_NGINX_CONFIG:-0}"
RUN_TESTS="${RUN_TESTS:-1}"
REQUIRE_CLEAN="${REQUIRE_CLEAN:-0}"
DRY_RUN="${DRY_RUN:-0}"
BACKEND_HEALTH_URL="${BACKEND_HEALTH_URL:-http://127.0.0.1:8080/api/health}"
LOCAL_SITE_URL="${LOCAL_SITE_URL:-http://127.0.0.1/}"
LOCAL_SITE_HOST="${LOCAL_SITE_HOST:-$DOMAIN}"
VERIFY_TIMEOUT="${VERIFY_TIMEOUT:-120}"
VERIFY_INTERVAL="${VERIFY_INTERVAL:-3}"
REMOTE_RELEASE_KEEP="${REMOTE_RELEASE_KEEP:-3}"
REQUIRE_MYSQL_CONFIG="${REQUIRE_MYSQL_CONFIG:-1}"

if [[ -z "${JAVA_HOME:-}" && -x /opt/homebrew/opt/openjdk@17/bin/java ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17
  export PATH="$JAVA_HOME/bin:$PATH"
fi

info() { printf '\033[36m[i]\033[0m %s\n' "$*"; }
ok() { printf '\033[32m[ok]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[!]\033[0m %s\n' "$*"; }
die() { printf '\033[31m[x]\033[0m %s\n' "$*" >&2; exit 1; }

print_command() {
  printf '  '
  printf '%q ' "$@"
  printf '\n'
}

run_cmd() {
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
  "DOMAIN=$DOMAIN" \
  "PUBLIC_URL=$PUBLIC_URL" \
  "BACKEND_HEALTH_URL=$BACKEND_HEALTH_URL" \
  "LOCAL_SITE_URL=$LOCAL_SITE_URL" \
  "LOCAL_SITE_HOST=$LOCAL_SITE_HOST"; do
  validate_simple_value "${pair%%=*}" "${pair#*=}"
done

[[ "$DEPLOY_PORT" =~ ^[0-9]+$ ]] || die "DEPLOY_PORT must be a number"
[[ "$RUN_TESTS" == "0" || "$RUN_TESTS" == "1" ]] || die "RUN_TESTS must be 0 or 1"
[[ "$REQUIRE_CLEAN" == "0" || "$REQUIRE_CLEAN" == "1" ]] || die "REQUIRE_CLEAN must be 0 or 1"
[[ "$DRY_RUN" == "0" || "$DRY_RUN" == "1" ]] || die "DRY_RUN must be 0 or 1"
[[ "$FORCE_NGINX_CONFIG" == "0" || "$FORCE_NGINX_CONFIG" == "1" ]] || die "FORCE_NGINX_CONFIG must be 0 or 1"
[[ "$REQUIRE_MYSQL_CONFIG" == "0" || "$REQUIRE_MYSQL_CONFIG" == "1" ]] || die "REQUIRE_MYSQL_CONFIG must be 0 or 1"
[[ "$VERIFY_TIMEOUT" =~ ^[0-9]+$ ]] || die "VERIFY_TIMEOUT must be a number"
[[ "$VERIFY_INTERVAL" =~ ^[0-9]+$ ]] || die "VERIFY_INTERVAL must be a number"
[[ "$REMOTE_RELEASE_KEEP" =~ ^[0-9]+$ ]] || die "REMOTE_RELEASE_KEEP must be a number"
(( REMOTE_RELEASE_KEEP >= 1 )) || die "REMOTE_RELEASE_KEEP must be at least 1"

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

git_status_for_manifest() {
  git -C "$ROOT" status --porcelain --untracked-files=all -- . \
    ':(exclude).release/**' \
    ':(exclude)server-upload/**' \
    ':(exclude)outputs/**' 2>/dev/null || true
}

write_remote_install_script() {
  cat > "$PACKAGE_ROOT/install-linux.sh" <<'INSTALL_SCRIPT'
#!/usr/bin/env bash
set -euo pipefail

INSTALL_DIR="${INSTALL_DIR:-/opt/web-homepage}"
CONFIG_DIR="${CONFIG_DIR:-/etc/web-homepage}"
ENV_FILE="$CONFIG_DIR/web.env"
SERVICE_NAME="${SERVICE_NAME:-web-backen}"
NGINX_SITE_NAME="${NGINX_SITE_NAME:-web-homepage}"
DOMAIN="${DOMAIN:-_}"
FORCE_NGINX_CONFIG="${FORCE_NGINX_CONFIG:-0}"
REQUIRE_MYSQL_CONFIG="${REQUIRE_MYSQL_CONFIG:-1}"
CURRENT_DIR="$(cd "$(dirname "$0")" && pwd)"

info() { printf '\033[36m[i]\033[0m %s\n' "$*"; }
ok() { printf '\033[32m[ok]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[!]\033[0m %s\n' "$*"; }
die() { printf '\033[31m[x]\033[0m %s\n' "$*" >&2; exit 1; }

if [[ "$(id -u)" -ne 0 ]]; then
  die "Please run as root, for example: sudo ./install-linux.sh"
fi

command -v java >/dev/null 2>&1 || die "java not found. Install Java 17 first."
command -v uv >/dev/null 2>&1 || die "uv not found. Runtime PPT/PDF features depend on uv run."

if ! java -version 2>&1 | grep -Eq 'version "17|version "18|version "19|version "2[0-9]|openjdk version "17|openjdk version "18|openjdk version "19|openjdk version "2[0-9]'; then
  warn "Java exists, but it may be older than 17. Spring Boot 3 requires Java 17+."
fi

if command -v fc-list >/dev/null 2>&1; then
  if ! fc-list | grep -Eiq 'Noto.*CJK|Source Han|WenQuanYi|Microsoft YaHei|SimSun|PingFang|Heiti'; then
    warn "No common CJK font found via fc-list. Generated PPT/PDF Chinese text may render poorly."
  fi
else
  warn "fontconfig fc-list not found. Install fontconfig and CJK fonts for reliable Chinese PPT/PDF rendering."
fi

mkdir -p "$CONFIG_DIR"
if [[ ! -f "$ENV_FILE" ]]; then
  cp "$CURRENT_DIR/web.env.example" "$ENV_FILE"
  chmod 600 "$ENV_FILE"
  warn "Created $ENV_FILE from template."
  warn "Fill ROOT_PASSWORD, database credentials, API keys, then rerun deployment."
  exit 2
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

[[ -n "${ROOT_PASSWORD:-}" && "${#ROOT_PASSWORD}" -ge 6 ]] || die "ROOT_PASSWORD in $ENV_FILE must be set and at least 6 characters."

if [[ "$REQUIRE_MYSQL_CONFIG" == "1" ]]; then
  [[ -n "${DB_URL:-}" && "$DB_URL" == jdbc:mysql:* ]] || die "DB_URL in $ENV_FILE must point to MySQL, or set REQUIRE_MYSQL_CONFIG=0."
  [[ -n "${DB_USERNAME:-}" ]] || die "DB_USERNAME in $ENV_FILE is required."
  [[ -n "${DB_PASSWORD:-}" ]] || die "DB_PASSWORD in $ENV_FILE is required."
fi

if [[ -z "${ZOTERO_API_KEY:-}" || -z "${ZOTERO_USER_ID:-}" ]]; then
  warn "ZOTERO_API_KEY or ZOTERO_USER_ID is empty; Publications data may not warm up."
fi
if [[ -z "${LLM_API_KEY:-}" && -z "${BABELDOC_OPENAI_API_KEY:-}" ]]; then
  warn "LLM/BabelDOC API key is empty; translation and PPT generation may fail."
fi

if systemctl list-unit-files "$SERVICE_NAME.service" >/dev/null 2>&1; then
  info "Stopping existing backend service before replacing files..."
  systemctl stop "$SERVICE_NAME.service" || true
fi

info "Installing files to $INSTALL_DIR..."
mkdir -p "$INSTALL_DIR/backen" "$INSTALL_DIR/front" "$INSTALL_DIR/.run/logs"
cp "$CURRENT_DIR/backen/backen.jar" "$INSTALL_DIR/backen/backen.jar.new"
chmod 644 "$INSTALL_DIR/backen/backen.jar.new"
mv -f "$INSTALL_DIR/backen/backen.jar.new" "$INSTALL_DIR/backen/backen.jar"
rm -rf "$INSTALL_DIR/backen/scripts"
cp -R "$CURRENT_DIR/backen/scripts" "$INSTALL_DIR/backen/scripts"
rm -rf "$INSTALL_DIR/front/dist"
cp -R "$CURRENT_DIR/front/dist" "$INSTALL_DIR/front/dist"

for document in README.md DEPLOYMENT.md AGENTS.md WORKLOG.md MAINTENANCE.md release-manifest.txt; do
  if [[ -f "$CURRENT_DIR/$document" ]]; then
    cp "$CURRENT_DIR/$document" "$INSTALL_DIR/$document"
    chmod 644 "$INSTALL_DIR/$document"
  fi
done

if [[ -f "$CURRENT_DIR/.run/github-projects.json" && ! -f "$INSTALL_DIR/.run/github-projects.json" ]]; then
  cp "$CURRENT_DIR/.run/github-projects.json" "$INSTALL_DIR/.run/github-projects.json"
fi

info "Installing systemd service..."
cat > "/etc/systemd/system/$SERVICE_NAME.service" <<SERVICE_UNIT
[Unit]
Description=Web Homepage Spring Boot Backend
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=$INSTALL_DIR/backen
EnvironmentFile=$ENV_FILE
Environment=HOME=/home/admin
Environment=PATH=/usr/local/bin:/usr/bin:/bin
Environment="JAVA_TOOL_OPTIONS=-Xms128m -Xmx768m -XX:+UseG1GC"
ExecStart=/usr/bin/env java -jar $INSTALL_DIR/backen/backen.jar
Restart=always
RestartSec=5
SuccessExitStatus=143
MemoryAccounting=yes
MemoryHigh=2200M
MemoryMax=2800M
TasksMax=256

[Install]
WantedBy=multi-user.target
SERVICE_UNIT

systemctl daemon-reload
systemctl enable "$SERVICE_NAME.service" >/dev/null
systemctl start "$SERVICE_NAME.service"

if [[ -d "/etc/systemd/system/$SERVICE_NAME.service.d" ]]; then
  warn "Preserved existing systemd drop-ins in /etc/systemd/system/$SERVICE_NAME.service.d"
  warn "Run 'systemctl show $SERVICE_NAME -p Environment' to confirm outbound proxy JAVA_TOOL_OPTIONS if needed."
fi

if command -v nginx >/dev/null 2>&1; then
  info "Installing Nginx site..."
  if [[ -d /etc/nginx/sites-available ]]; then
    NGINX_CONF="/etc/nginx/sites-available/$NGINX_SITE_NAME"
  elif [[ -d /etc/nginx/conf.d ]]; then
    NGINX_CONF="/etc/nginx/conf.d/$NGINX_SITE_NAME.conf"
  else
    NGINX_CONF="/etc/nginx/$NGINX_SITE_NAME.conf"
  fi

  if [[ -f "$NGINX_CONF" && "$FORCE_NGINX_CONFIG" == "1" ]] && grep -Eq 'ssl_certificate|managed by Certbot' "$NGINX_CONF"; then
    warn "Existing Nginx config contains SSL/Certbot directives; refusing to overwrite it."
  elif [[ -f "$NGINX_CONF" && "$FORCE_NGINX_CONFIG" != "1" ]]; then
    ok "Keeping existing Nginx config: $NGINX_CONF"
  else
    cat > "$NGINX_CONF" <<NGINX_CONF_BODY
server {
    listen 80;
    server_name $DOMAIN;

    root $INSTALL_DIR/front/dist;
    index index.html;
    client_max_body_size 64m;

    location ~ ^/api/(translate/stream|ppt-generate/stream|zotero/file)/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_buffering off;
        proxy_request_buffering off;
        proxy_cache off;
        proxy_read_timeout 21600s;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_read_timeout 21600s;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }

    location / {
        try_files \$uri \$uri/ /index.html;
    }
}
NGINX_CONF_BODY
    ok "Installed Nginx config: $NGINX_CONF"
  fi

  if [[ -d /etc/nginx/sites-enabled && "$NGINX_CONF" == /etc/nginx/sites-available/* ]]; then
    ln -sfn "/etc/nginx/sites-available/$NGINX_SITE_NAME" "/etc/nginx/sites-enabled/$NGINX_SITE_NAME"
  fi
  nginx -t
  systemctl reload nginx || systemctl restart nginx
else
  warn "Nginx not found. Static files are installed at $INSTALL_DIR/front/dist and backend listens on :8080."
fi

ok "Backend service: systemctl status $SERVICE_NAME"
ok "Backend logs: journalctl -u $SERVICE_NAME -f"
ok "Environment file: $ENV_FILE"
ok "Frontend root: $INSTALL_DIR/front/dist"
INSTALL_SCRIPT
  chmod +x "$PACKAGE_ROOT/install-linux.sh"
}

build_release() {
  local version archive
  version="$(date +%Y%m%d-%H%M%S)"
  archive="$RELEASE_DIR/web-homepage-$version.tar.gz"

  if [[ "$DRY_RUN" == "1" ]]; then
    info "Dry run: release archive would be created under $RELEASE_DIR"
    ARCHIVE="$RELEASE_DIR/web-homepage-<timestamp>.tar.gz"
    ARCHIVE_NAME="web-homepage-<timestamp>.tar.gz"
    VERSION="<timestamp>"
    return 0
  fi

  rm -rf "$PACKAGE_ROOT"
  mkdir -p "$PACKAGE_ROOT/backen" "$PACKAGE_ROOT/front" "$NPM_CACHE_DIR"

  if [[ "$RUN_TESTS" == "1" ]]; then
    info "Running backend tests..."
    (cd "$ROOT/backen" && mvn -q test)
  fi

  info "Building backend jar..."
  (cd "$ROOT/backen" && mvn -q -DskipTests package)

  info "Building frontend static files..."
  if [[ -f "$ROOT/front/package-lock.json" ]]; then
    (cd "$ROOT/front" && npm ci --cache "$NPM_CACHE_DIR" && VITE_API_BASE_URL=/api npm run build)
  else
    (cd "$ROOT/front" && npm install --cache "$NPM_CACHE_DIR" && VITE_API_BASE_URL=/api npm run build)
  fi

  if grep -Rqs 'api\.example\.com' "$ROOT/front/dist"; then
    die "Frontend build contains placeholder API host api.example.com"
  fi

  info "Collecting release files..."
  cp "$ROOT/backen/target/backen-0.0.1-SNAPSHOT.jar" "$PACKAGE_ROOT/backen/backen.jar"
  cp -R "$ROOT/backen/scripts" "$PACKAGE_ROOT/backen/scripts"
  find "$PACKAGE_ROOT/backen/scripts" -type d -name node_modules -prune -exec rm -rf {} +
  find "$PACKAGE_ROOT/backen/scripts" \( -type d -name __pycache__ -o -type d -name .pytest_cache \) -prune -exec rm -rf {} +
  find "$PACKAGE_ROOT/backen/scripts" -type f \( -name '*.pyc' -o -name '*.pyo' \) -delete
  cp -R "$ROOT/front/dist" "$PACKAGE_ROOT/front/dist"
  cp "$ROOT/.env.local.example" "$PACKAGE_ROOT/web.env.example"

  for document in README.md DEPLOYMENT.md AGENTS.md WORKLOG.md MAINTENANCE.md; do
    if [[ -f "$ROOT/$document" ]]; then
      cp "$ROOT/$document" "$PACKAGE_ROOT/$document"
    fi
  done

  if [[ -f "$ROOT/.run/github-projects.json" ]]; then
    mkdir -p "$PACKAGE_ROOT/.run"
    cp "$ROOT/.run/github-projects.json" "$PACKAGE_ROOT/.run/github-projects.json"
  fi

  {
    echo "version=$version"
    echo "built_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "git_commit=$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"
    echo "git_branch=$(git -C "$ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
    changed_paths="$(git_status_for_manifest)"
    if [[ -n "$changed_paths" ]]; then
      echo "git_dirty=true"
      echo "changed_paths<<EOF"
      printf '%s\n' "$changed_paths"
      echo "EOF"
    else
      echo "git_dirty=false"
    fi
  } > "$PACKAGE_ROOT/release-manifest.txt"

  write_remote_install_script

  info "Creating archive..."
  (cd "$RELEASE_DIR" && tar -czf "$archive" web-homepage)

  ARCHIVE="$archive"
  ARCHIVE_NAME="$(basename "$ARCHIVE")"
  VERSION="${ARCHIVE_NAME#web-homepage-}"
  VERSION="${VERSION%.tar.gz}"
  ok "Release package created: $ARCHIVE"
}

info "Deployment target: $TARGET:$REMOTE_DIR"
info "Server config will be preserved: $CONFIG_DIR/web.env"
[[ -n "$DEPLOY_IDENTITY_FILE" ]] && info "SSH private key: $DEPLOY_IDENTITY_FILE"

if [[ -n "$(git -C "$ROOT" status --porcelain)" ]]; then
  if [[ "$REQUIRE_CLEAN" == "1" ]]; then
    die "Git worktree is not clean. Commit or stash changes, or run with REQUIRE_CLEAN=0."
  fi
  warn "Git worktree contains uncommitted changes; they will be included in the release."
fi

build_release

REMOTE_ARCHIVE="$REMOTE_UPLOAD_DIR/$ARCHIVE_NAME"
REMOTE_STAGE="$REMOTE_UPLOAD_DIR/stage-$VERSION"
REMOTE_PACKAGE="$REMOTE_STAGE/web-homepage"
REMOTE_BACKUP="$REMOTE_UPLOAD_DIR/web-homepage-backup-$VERSION.tar.gz"
REMOTE_PARENT="$(dirname "$REMOTE_DIR")"
REMOTE_BASENAME="$(basename "$REMOTE_DIR")"
INSTALL_ATTEMPTED=0
LOCAL_VERIFY_PASSED=0

cleanup_remote_stage() {
  [[ "${DRY_RUN:-0}" == "1" ]] && return 0
  [[ -n "${REMOTE_STAGE:-}" ]] || return 0
  "${SSH[@]}" "rm -rf '$REMOTE_STAGE'" >/dev/null 2>&1 || true
}

rollback_remote_release() {
  [[ "${DRY_RUN:-0}" == "1" ]] && return 0
  [[ "${INSTALL_ATTEMPTED:-0}" == "1" ]] || return 0
  [[ "${LOCAL_VERIFY_PASSED:-0}" != "1" ]] || return 0
  warn "Install or local verification failed; attempting to restore server backup: $REMOTE_BACKUP"
  "${SSH_TTY[@]}" \
    "set -e; \
    if [ -f '$REMOTE_BACKUP' ]; then \
      sudo systemctl stop '$SERVICE_NAME.service' || true; \
      sudo rm -rf '$REMOTE_DIR'; \
      sudo mkdir -p '$REMOTE_PARENT'; \
      sudo tar -xzf '$REMOTE_BACKUP' -C '$REMOTE_PARENT'; \
      sudo systemctl daemon-reload; \
      sudo systemctl start '$SERVICE_NAME.service'; \
      if command -v nginx >/dev/null 2>&1; then sudo nginx -t && sudo systemctl reload nginx || true; fi; \
    else \
      echo '[!] Backup archive missing: $REMOTE_BACKUP' >&2; \
      exit 1; \
    fi" || true
}

on_exit() {
  local code=$?
  if [[ "$code" -ne 0 ]]; then
    rollback_remote_release
  fi
  cleanup_remote_stage
  exit "$code"
}
trap on_exit EXIT

info "Preparing remote release directory..."
run_cmd "${SSH[@]}" "mkdir -p '$REMOTE_UPLOAD_DIR'"

info "Uploading $ARCHIVE_NAME..."
run_cmd "${SCP[@]}" "$ARCHIVE" "$TARGET:$REMOTE_ARCHIVE"

info "Creating server backup and extracting release..."
run_cmd "${SSH[@]}" \
  "set -e; rm -rf '$REMOTE_STAGE'; mkdir -p '$REMOTE_STAGE'; if [ -d '$REMOTE_DIR' ]; then tar -czf '$REMOTE_BACKUP' -C '$REMOTE_PARENT' '$REMOTE_BASENAME'; fi; tar -xzf '$REMOTE_ARCHIVE' -C '$REMOTE_STAGE'"

info "Installing release. sudo may ask for the server password..."
INSTALL_ATTEMPTED=1
run_cmd "${SSH_TTY[@]}" \
  "sudo env INSTALL_DIR='$REMOTE_DIR' CONFIG_DIR='$CONFIG_DIR' SERVICE_NAME='$SERVICE_NAME' NGINX_SITE_NAME='$NGINX_SITE_NAME' DOMAIN='$DOMAIN' FORCE_NGINX_CONFIG='$FORCE_NGINX_CONFIG' REQUIRE_MYSQL_CONFIG='$REQUIRE_MYSQL_CONFIG' bash '$REMOTE_PACKAGE/install-linux.sh'"

info "Verifying backend, Nginx, and local server response..."
run_cmd "${SSH[@]}" \
  "set -e; \
  systemctl is-active '$SERVICE_NAME.service' >/dev/null; \
  deadline=\$((\$(date +%s) + $VERIFY_TIMEOUT)); \
  until curl -fsS '$BACKEND_HEALTH_URL' >/dev/null 2>&1; do \
    if [ \"\$(date +%s)\" -ge \"\$deadline\" ]; then \
      echo '[x] Backend health check timed out: $BACKEND_HEALTH_URL' >&2; \
      systemctl status '$SERVICE_NAME.service' -n 50 --no-pager >&2 || true; \
      journalctl -u '$SERVICE_NAME.service' -n 120 --no-pager >&2 || true; \
      exit 1; \
    fi; \
    sleep '$VERIFY_INTERVAL'; \
  done; \
  curl -fsSI -H 'Host: $LOCAL_SITE_HOST' '$LOCAL_SITE_URL' >/dev/null"
LOCAL_VERIFY_PASSED=1

info "Verifying public response: $PUBLIC_URL"
run_cmd "${SSH[@]}" "curl -fsSI '$PUBLIC_URL' >/dev/null"

info "Cleaning extracted staging directory..."
cleanup_remote_stage

info "Pruning old release archives on server..."
run_cmd "${SSH[@]}" \
  "set -e; \
  cd '$REMOTE_UPLOAD_DIR'; \
  find . -maxdepth 1 -type f -name 'web-homepage-[0-9]*.tar.gz' -printf '%T@ %p\n' \
    | sort -rn | awk 'NR>$REMOTE_RELEASE_KEEP {print substr(\$0, index(\$0,\$2))}' \
    | xargs -r rm -f --; \
  find . -maxdepth 1 -type f -name 'web-homepage-backup-[0-9]*.tar.gz' -printf '%T@ %p\n' \
    | sort -rn | awk 'NR>$REMOTE_RELEASE_KEEP {print substr(\$0, index(\$0,\$2))}' \
    | xargs -r rm -f --; \
  du -sh '$REMOTE_UPLOAD_DIR'"

if [[ "$DRY_RUN" == "1" ]]; then
  ok "Dry run completed. No files were uploaded or installed."
else
  ok "Deployment completed: $PUBLIC_URL"
fi
ok "Server backup: $REMOTE_BACKUP"
ok "Uploaded archive: $REMOTE_ARCHIVE"
