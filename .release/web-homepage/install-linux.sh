#!/usr/bin/env bash
# Install or update the Web project on a Linux server.
# Run from the extracted release directory: sudo ./install-linux.sh

set -euo pipefail

APP_NAME="${APP_NAME:-web-homepage}"
INSTALL_DIR="${INSTALL_DIR:-/opt/$APP_NAME}"
CONFIG_DIR="${CONFIG_DIR:-/etc/$APP_NAME}"
ENV_FILE="$CONFIG_DIR/web.env"
SERVICE_NAME="${SERVICE_NAME:-web-backen}"
NGINX_SITE_NAME="${NGINX_SITE_NAME:-web-homepage}"
DOMAIN="${DOMAIN:-_}"
CURRENT_DIR="$(cd "$(dirname "$0")" && pwd)"

info() { printf '\033[36m[i]\033[0m %s\n' "$*"; }
ok() { printf '\033[32m[✓]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[!]\033[0m %s\n' "$*"; }

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Please run as root, for example: sudo ./install-linux.sh"
  exit 1
fi

command -v java >/dev/null 2>&1 || {
  echo "java not found. Install Java 17 first, for example: apt install openjdk-17-jre-headless"
  exit 1
}

if ! java -version 2>&1 | grep -Eq 'version "17|version "18|version "19|version "2[0-9]|openjdk version "17|openjdk version "18|openjdk version "19|openjdk version "2[0-9]'; then
  warn "Java exists, but it may be older than 17. Spring Boot 3 requires Java 17+."
fi

info "Installing files to $INSTALL_DIR ..."
mkdir -p "$INSTALL_DIR/backen" "$INSTALL_DIR/front" "$INSTALL_DIR/.run/logs" "$CONFIG_DIR"
JAR_TMP="$INSTALL_DIR/backen/backen.jar.new"
cp "$CURRENT_DIR/backen/backen.jar" "$JAR_TMP"
chmod 644 "$JAR_TMP"
mv -f "$JAR_TMP" "$INSTALL_DIR/backen/backen.jar"
rm -rf "$INSTALL_DIR/backen/scripts"
cp -R "$CURRENT_DIR/backen/scripts" "$INSTALL_DIR/backen/scripts"
rm -rf "$INSTALL_DIR/front/dist"
cp -R "$CURRENT_DIR/front/dist" "$INSTALL_DIR/front/dist"

if [[ -f "$CURRENT_DIR/.run/github-projects.json" && ! -f "$INSTALL_DIR/.run/github-projects.json" ]]; then
  cp "$CURRENT_DIR/.run/github-projects.json" "$INSTALL_DIR/.run/github-projects.json"
fi

if [[ ! -f "$ENV_FILE" ]]; then
  cp "$CURRENT_DIR/web.env.example" "$ENV_FILE"
  chmod 600 "$ENV_FILE"
  warn "Created $ENV_FILE. Fill in ZOTERO_API_KEY, ZOTERO_USER_ID, ADMIN_KEY and optional translation/OpenClaw settings."
else
  ok "Keeping existing env file: $ENV_FILE"
fi

info "Installing systemd service ..."
sed "s#__INSTALL_DIR__#$INSTALL_DIR#g; s#__ENV_FILE__#$ENV_FILE#g" \
  "$CURRENT_DIR/deploy/web-backen.service.template" > "/etc/systemd/system/$SERVICE_NAME.service"

systemctl daemon-reload
systemctl enable "$SERVICE_NAME.service" >/dev/null
systemctl restart "$SERVICE_NAME.service"

if command -v nginx >/dev/null 2>&1; then
  info "Installing Nginx site ..."
  if [[ -d /etc/nginx/sites-available ]]; then
    NGINX_CONF="/etc/nginx/sites-available/$NGINX_SITE_NAME"
  elif [[ -d /etc/nginx/conf.d ]]; then
    NGINX_CONF="/etc/nginx/conf.d/$NGINX_SITE_NAME.conf"
  else
    NGINX_CONF="/etc/nginx/$NGINX_SITE_NAME.conf"
  fi

  sed "s#__INSTALL_DIR__#$INSTALL_DIR#g; s#__DOMAIN__#$DOMAIN#g" \
    "$CURRENT_DIR/deploy/nginx.conf.template" > "$NGINX_CONF"
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
