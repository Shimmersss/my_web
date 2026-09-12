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
  if local_config_mode="$(stat -f '%Lp' "$LOCAL_CONFIG" 2>/dev/null)"; then
    :
  else
    local_config_mode="$(stat -c '%a' "$LOCAL_CONFIG" 2>/dev/null || true)"
  fi
  if [[ "$local_config_mode" != "600" ]]; then
    printf '[x] %s must have mode 0600 (current: %s). Run: chmod 600 %q\n' \
      "$LOCAL_CONFIG" "${local_config_mode:-unknown}" "$LOCAL_CONFIG" >&2
    exit 1
  fi
  # shellcheck disable=SC1090
  source "$LOCAL_CONFIG"
fi

DEPLOY_HOST="${DEPLOY_HOST:-}"
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
BUILD_ONLY="${BUILD_ONLY:-0}"
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

for command in git mvn node npm ssh scp curl tar; do
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
[[ "$BUILD_ONLY" == "0" || "$BUILD_ONLY" == "1" ]] || die "BUILD_ONLY must be 0 or 1"
[[ "$FORCE_NGINX_CONFIG" == "0" || "$FORCE_NGINX_CONFIG" == "1" ]] || die "FORCE_NGINX_CONFIG must be 0 or 1"
[[ "$REQUIRE_MYSQL_CONFIG" == "0" || "$REQUIRE_MYSQL_CONFIG" == "1" ]] || die "REQUIRE_MYSQL_CONFIG must be 0 or 1"
[[ "$VERIFY_TIMEOUT" =~ ^[0-9]+$ ]] || die "VERIFY_TIMEOUT must be a number"
[[ "$VERIFY_INTERVAL" =~ ^[0-9]+$ ]] || die "VERIFY_INTERVAL must be a number"
[[ "$REMOTE_RELEASE_KEEP" =~ ^[0-9]+$ ]] || die "REMOTE_RELEASE_KEEP must be a number"
(( REMOTE_RELEASE_KEEP >= 1 )) || die "REMOTE_RELEASE_KEEP must be at least 1"
if [[ "$BUILD_ONLY" != "1" ]]; then
  [[ -n "$DEPLOY_HOST" ]] || die "DEPLOY_HOST is required. Put it in the ignored 0600 .deploy.local file or export it in the shell."
fi

TARGET="$DEPLOY_USER@${DEPLOY_HOST:-build-only.invalid}"
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
command -v python3 >/dev/null 2>&1 || die "python3 not found. Deployment safety checks require Python 3."
command -v node >/dev/null 2>&1 || die "node not found. The presentation Agent requires Node.js 20+."
command -v npm >/dev/null 2>&1 || die "npm not found. Install npm alongside Node.js."
node -e 'if (Number(process.versions.node.split(".")[0]) < 20) process.exit(1)' \
  || die "Node.js 20+ is required by the presentation Agent."
command -v soffice >/dev/null 2>&1 || die "soffice not found. Install stable LibreOffice; the Agent has no secondary renderer."
if soffice --version 2>&1 | grep -Eiq 'dev|alpha|beta|rc[0-9]*'; then
  die "A stable LibreOffice release is required; development and prerelease builds are rejected."
fi
command -v pdftoppm >/dev/null 2>&1 || die "pdftoppm not found. Install poppler; the Agent has no secondary renderer."

if ! java -version 2>&1 | grep -Eq 'version "17|version "18|version "19|version "2[0-9]|openjdk version "17|openjdk version "18|openjdk version "19|openjdk version "2[0-9]'; then
  warn "Java exists, but it may be older than 17. Spring Boot 3 requires Java 17+."
fi

if command -v fc-list >/dev/null 2>&1; then
  if [[ -z "$(fc-list | grep -Ei 'Noto.*CJK|Source Han|WenQuanYi|Microsoft YaHei|SimSun|PingFang|Heiti' | sed -n '1p')" ]]; then
    die "No CJK font found. Install fonts-noto-cjk before deploying the presentation Agent."
  fi
else
  die "fontconfig fc-list not found. Install fontconfig and fonts-noto-cjk."
fi

mkdir -p "$CONFIG_DIR"
if [[ ! -f "$ENV_FILE" ]]; then
  cp "$CURRENT_DIR/web.env.example" "$ENV_FILE"
  chmod 600 "$ENV_FILE"
  warn "Created $ENV_FILE from template."
  warn "Fill ROOT_PASSWORD, database credentials, API keys, then rerun deployment."
  exit 2
fi
chmod 600 "$ENV_FILE"

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

mkdir -p "$INSTALL_DIR/.run"
DEPLOYMENT_LOCK="${DEPLOYMENT_LOCK_PATH:-$INSTALL_DIR/.run/deployment.lock}"
mkdir -p "$(dirname "$DEPLOYMENT_LOCK")"
[[ ! -e "$DEPLOYMENT_LOCK" ]] || die "Maintenance lock already exists; inspect its owner before deployment."
( set -o noclobber; printf 'deployment\n' > "$DEPLOYMENT_LOCK" )
INSTALL_MUTATED=0
cleanup_deployment_lock() {
  if [[ "$INSTALL_MUTATED" == "0" ]]; then rm -f "$DEPLOYMENT_LOCK"; fi
}
# Once files change, only the verified outer release flow may reopen admission.
trap cleanup_deployment_lock EXIT

[[ -n "${ROOT_PASSWORD:-}" && "${#ROOT_PASSWORD}" -ge 6 ]] || die "ROOT_PASSWORD in $ENV_FILE must be set and at least 6 characters."
PPT_CHROME_COMMAND="${PPT_GENERATION_CHROME_COMMAND:-}"
if [[ -z "$PPT_CHROME_COMMAND" ]]; then
  for candidate in /usr/bin/chromium /usr/bin/chromium-browser /usr/bin/google-chrome; do
    if [[ -x "$candidate" ]]; then
      PPT_CHROME_COMMAND="$candidate"
      break
    fi
  done
fi
[[ -n "$PPT_CHROME_COMMAND" && -x "$PPT_CHROME_COMMAND" ]] \
  || die "Chromium/Chrome not found. Set PPT_GENERATION_CHROME_COMMAND to an executable path."

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

scan_active_tasks() {
  python3 "$CURRENT_DIR/task_state.py" "$INSTALL_DIR"
}

# Let requests that passed the pre-lock check persist their "creating" marker,
# then scan the exact runtime storage paths before stopping the service.
sleep 2
if ! scan_active_tasks; then
  exit 42
fi

WAS_RUNNING=0
if systemctl is-active --quiet "$SERVICE_NAME.service"; then WAS_RUNNING=1; fi
if systemctl cat "$SERVICE_NAME.service" >/dev/null 2>&1; then
  info "Stopping existing backend service before replacing files..."
  systemctl stop "$SERVICE_NAME.service"
  if ! scan_active_tasks; then
    rm -f "$DEPLOYMENT_LOCK"
    systemctl start "$SERVICE_NAME.service" || true
    exit 42
  fi
fi

RECOVERY_BACKUP="${RECOVERY_BACKUP:-$INSTALL_DIR/../.web-homepage-releases/recovery-$(date +%Y%m%d-%H%M%S)}"
if ! python3 "$CURRENT_DIR/release_backup.py" "$INSTALL_DIR" "$ENV_FILE" "$RECOVERY_BACKUP"; then
  if [[ "$WAS_RUNNING" == "1" ]] && ! systemctl start "$SERVICE_NAME.service"; then
    INSTALL_MUTATED=1
    die "Backup failed and previous service could not restart; maintenance lock retained."
  fi
  exit 43
fi
info "Private SQL/runtime/configuration backup verified: $RECOVERY_BACKUP"

INSTALL_MUTATED=1
info "Installing files to $INSTALL_DIR..."
mkdir -p "$INSTALL_DIR/backen" "$INSTALL_DIR/front" "$INSTALL_DIR/.run/logs"
cp "$CURRENT_DIR/backen/backen.jar" "$INSTALL_DIR/backen/backen.jar.new"
chmod 644 "$INSTALL_DIR/backen/backen.jar.new"
mv -f "$INSTALL_DIR/backen/backen.jar.new" "$INSTALL_DIR/backen/backen.jar"
rm -rf "$INSTALL_DIR/backen/scripts"
cp -R "$CURRENT_DIR/backen/scripts" "$INSTALL_DIR/backen/scripts"
rm -rf "$INSTALL_DIR/vendor/open-kimi-ppt-skill"
mkdir -p "$INSTALL_DIR/vendor"
cp -R "$CURRENT_DIR/vendor/open-kimi-ppt-skill" "$INSTALL_DIR/vendor/open-kimi-ppt-skill"
rm -rf "$INSTALL_DIR/.agents"
cp -R "$CURRENT_DIR/.agents" "$INSTALL_DIR/.agents"
if [[ -f "$CURRENT_DIR/backen/package.json" ]]; then
  cp "$CURRENT_DIR/backen/package.json" "$INSTALL_DIR/backen/package.json"
  [[ ! -f "$CURRENT_DIR/backen/package-lock.json" ]] || cp "$CURRENT_DIR/backen/package-lock.json" "$INSTALL_DIR/backen/package-lock.json"
  info "Installing the locked Codex CLI, HTML worker, and presentation runtime..."
  (cd "$INSTALL_DIR/backen" && npm ci --omit=dev --ignore-scripts --no-audit --no-fund)
  # Codex 0.147.0 requires this multi-call helper to be visible to agent shell
  # commands. Keep it inside the release's node_modules rather than /usr/local.
  (cd "$INSTALL_DIR/backen" && npm run prepare:codex-linux-sandbox && npm run verify:codex-linux-sandbox)
fi
rm -rf "$INSTALL_DIR/front/dist"
cp -R "$CURRENT_DIR/front/dist" "$INSTALL_DIR/front/dist"

for document in README.md DEPLOYMENT.md AGENTS.md WORKLOG.md MAINTENANCE.md release-manifest.txt; do
  if [[ -f "$CURRENT_DIR/$document" ]]; then
    cp "$CURRENT_DIR/$document" "$INSTALL_DIR/$document"
    chmod 644 "$INSTALL_DIR/$document"
  fi
done

info "Verifying that headless LibreOffice really renders newly authored CJK text..."
(cd "$INSTALL_DIR/backen" && npm run preflight:ppt-fonts)

if [[ -f "$CURRENT_DIR/.run/github-projects.json" && ! -f "$INSTALL_DIR/.run/github-projects.json" ]]; then
  cp "$CURRENT_DIR/.run/github-projects.json" "$INSTALL_DIR/.run/github-projects.json"
fi
mkdir -p "$INSTALL_DIR/.run/ppt-generation-tasks"
PPT_CODEX_MIGRATION_MARKER="$INSTALL_DIR/.run/ppt-generation-tasks/.codex-pptd-v1"
if [[ ! -f "$PPT_CODEX_MIGRATION_MARKER" ]]; then
  PPT_TASK_ARCHIVE="${PPT_PRE_CODEX_ARCHIVE:-$INSTALL_DIR/../.web-homepage-releases/ppt-generation-tasks-pre-codex-$(date +%Y%m%d-%H%M%S).tar.gz}"
  mkdir -p "$(dirname "$PPT_TASK_ARCHIVE")"
  tar -czf "$PPT_TASK_ARCHIVE" -C "$INSTALL_DIR/.run" ppt-generation-tasks
  tar -tzf "$PPT_TASK_ARCHIVE" >/dev/null
  find "$INSTALL_DIR/.run/ppt-generation-tasks" -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
  touch "$PPT_CODEX_MIGRATION_MARKER"
  chmod 600 "$PPT_TASK_ARCHIVE"
  info "Archived and cleared pre-Codex PPT tasks: $PPT_TASK_ARCHIVE"
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
Environment=DEPLOYMENT_LOCK_PATH=$DEPLOYMENT_LOCK
Environment=PPT_GENERATION_CHROME_COMMAND=$PPT_CHROME_COMMAND
Environment=SERVER_ADDRESS=127.0.0.1
Environment=AUTH_COOKIE_SECURE=true
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
# Keep admission closed until the outer release verification succeeds.
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

    location ~ ^/api/(translate/stream|ppt-generate/stream|image-generate/stream|zotero/file)/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_buffering off;
        proxy_request_buffering off;
        proxy_cache off;
        proxy_read_timeout 21600s;
        add_header X-Accel-Buffering no always;
        add_header Cache-Control "no-store" always;
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
  cat > "$PACKAGE_ROOT/resume-admission.sh" <<'RESUME_SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
source "$CONFIG_DIR/web.env"
lock="${DEPLOYMENT_LOCK_PATH:-$INSTALL_DIR/.run/deployment.lock}"
[[ "$(cat "$lock")" == deployment ]] || exit 1
rm -- "$lock"
RESUME_SCRIPT
  chmod +x "$PACKAGE_ROOT/resume-admission.sh"
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
    (cd "$ROOT/backen" && npm test)
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
  cp "$ROOT/deploy/task_state.py" "$PACKAGE_ROOT/task_state.py"
  cp "$ROOT/deploy/release_backup.py" "$PACKAGE_ROOT/release_backup.py"
  cp "$ROOT/backen/package.json" "$PACKAGE_ROOT/backen/package.json"
  cp "$ROOT/backen/package-lock.json" "$PACKAGE_ROOT/backen/package-lock.json"
  cp -R "$ROOT/backen/scripts" "$PACKAGE_ROOT/backen/scripts"
  cp -R "$ROOT/.agents" "$PACKAGE_ROOT/.agents"
  mkdir -p "$PACKAGE_ROOT/vendor"
  cp -R "$ROOT/vendor/open-kimi-ppt-skill" "$PACKAGE_ROOT/vendor/open-kimi-ppt-skill"
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
  # macOS tar otherwise stores AppleDouble/xattr metadata. GNU tar on the
  # Linux host treats those records as warnings and may return a failing status.
  local tar_metadata_flags=()
  if [[ "$(uname -s)" == "Darwin" ]]; then
    tar_metadata_flags=(--no-xattrs --no-acls --no-fflags --disable-copyfile)
  fi
  (cd "$RELEASE_DIR" && COPYFILE_DISABLE=1 COPY_EXTENDED_ATTRIBUTES_DISABLE=1 tar "${tar_metadata_flags[@]}" -czf "$archive" web-homepage)

  ARCHIVE="$archive"
  ARCHIVE_NAME="$(basename "$ARCHIVE")"
  VERSION="${ARCHIVE_NAME#web-homepage-}"
  VERSION="${VERSION%.tar.gz}"
  ok "Release package created: $ARCHIVE"
}

if [[ "$BUILD_ONLY" == "1" ]]; then
  info "Build-only mode: no deployment target required"
else
  info "Deployment target: $TARGET:$REMOTE_DIR"
fi
info "Server config will be preserved: $CONFIG_DIR/web.env"
[[ -n "$DEPLOY_IDENTITY_FILE" ]] && info "SSH private key: $DEPLOY_IDENTITY_FILE"

if [[ -n "$(git -C "$ROOT" status --porcelain)" ]]; then
  if [[ "$REQUIRE_CLEAN" == "1" ]]; then
    die "Git worktree is not clean. Commit or stash changes, or run with REQUIRE_CLEAN=0."
  fi
  warn "Git worktree contains uncommitted changes; they will be included in the release."
fi

build_release

if [[ "$BUILD_ONLY" == "1" ]]; then
  ok "Release package built locally only: $ARCHIVE"
  exit 0
fi

REMOTE_ARCHIVE="$REMOTE_UPLOAD_DIR/$ARCHIVE_NAME"
REMOTE_STAGE="$REMOTE_UPLOAD_DIR/stage-$VERSION"
REMOTE_PACKAGE="$REMOTE_STAGE/web-homepage"
REMOTE_BACKUP="$REMOTE_UPLOAD_DIR/web-homepage-backup-$VERSION.tar.gz"
REMOTE_PPT_TASK_ARCHIVE="$REMOTE_UPLOAD_DIR/ppt-generation-tasks-pre-codex-$VERSION.tar.gz"
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
  warn "Install or verification failed. Automatic data rollback is disabled: the new application may already have written SQL/runtime state."
  warn "Preserve the current installation and recovery bundle. Follow DEPLOYMENT.md to select a consistent recovery point: $REMOTE_BACKUP.recovery"

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
  "set -e; \
  rm -rf '$REMOTE_STAGE'; \
  mkdir -p '$REMOTE_STAGE'; \
  if [ -d '$REMOTE_DIR' ]; then tar --exclude='$REMOTE_BASENAME/.run' -czf '$REMOTE_BACKUP' -C '$REMOTE_PARENT' '$REMOTE_BASENAME'; fi; \
  tar --warning=no-unknown-keyword -xzf '$REMOTE_ARCHIVE' -C '$REMOTE_STAGE'"

info "Installing release. sudo may ask for the server password..."
INSTALL_ATTEMPTED=1
set +e
run_cmd "${SSH_TTY[@]}" \
  "sudo env INSTALL_DIR='$REMOTE_DIR' CONFIG_DIR='$CONFIG_DIR' SERVICE_NAME='$SERVICE_NAME' NGINX_SITE_NAME='$NGINX_SITE_NAME' DOMAIN='$DOMAIN' FORCE_NGINX_CONFIG='$FORCE_NGINX_CONFIG' REQUIRE_MYSQL_CONFIG='$REQUIRE_MYSQL_CONFIG' PPT_PRE_CODEX_ARCHIVE='$REMOTE_PPT_TASK_ARCHIVE' RECOVERY_BACKUP='$REMOTE_BACKUP.recovery' bash '$REMOTE_PACKAGE/install-linux.sh'"
INSTALL_STATUS=$?
set -e
if [[ "$INSTALL_STATUS" -eq 42 ]]; then
  INSTALL_ATTEMPTED=0
  warn "Deployment postponed because a task is active, compensating, or cannot be verified."
  exit 42
fi
if [[ "$INSTALL_STATUS" -ne 0 ]]; then
  exit "$INSTALL_STATUS"
fi

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

info "Reopening admission after local and public verification..."
run_cmd "${SSH_TTY[@]}" "sudo env INSTALL_DIR='$REMOTE_DIR' CONFIG_DIR='$CONFIG_DIR' bash '$REMOTE_PACKAGE/resume-admission.sh'"

info "Cleaning extracted staging directory..."
cleanup_remote_stage

info "Pruning old release archives on server..."
# Recovery bundles are deliberately retained for explicit review; no automatic SQL/runtime deletion.
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
ok "Code backup: $REMOTE_BACKUP"
ok "Private SQL/runtime/configuration recovery: $REMOTE_BACKUP.recovery"
ok "Uploaded archive: $REMOTE_ARCHIVE"
