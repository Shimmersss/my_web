#!/usr/bin/env bash
# Verify that the public hostname works while a known origin address cannot be
# reached with the production Host/SNI. Keep ORIGIN_ADDRESS outside Git.
# PUBLIC_HOST defaults to DOMAIN from the ignored 0600 .deploy.local file.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_CONFIG="${DEPLOY_LOCAL_CONFIG:-$ROOT/.deploy.local}"
if [[ -f "$LOCAL_CONFIG" ]]; then
  [[ "$(stat -f '%Lp' "$LOCAL_CONFIG" 2>/dev/null || stat -c '%a' "$LOCAL_CONFIG")" == "600" ]] \
    || { printf '[x] Local deploy config must have mode 0600: %s\n' "$LOCAL_CONFIG" >&2; exit 1; }
  # shellcheck disable=SC1090
  source "$LOCAL_CONFIG"
fi

PUBLIC_HOST="${PUBLIC_HOST:-${DOMAIN:-}}"
PUBLIC_URL="${PUBLIC_URL:-https://$PUBLIC_HOST/}"
ORIGIN_ADDRESS="${ORIGIN_ADDRESS:-}"
CONNECT_TIMEOUT="${CONNECT_TIMEOUT:-5}"
MAX_TIME="${MAX_TIME:-12}"

die() { printf '[x] %s\n' "$*" >&2; exit 1; }
ok() { printf '[ok] %s\n' "$*"; }

[[ -n "$PUBLIC_HOST" ]] || die "PUBLIC_HOST or DOMAIN is required (set DOMAIN in .deploy.local)."
[[ -n "$ORIGIN_ADDRESS" ]] || die "ORIGIN_ADDRESS is required and must be supplied outside Git."
[[ "$PUBLIC_HOST" != *$'\n'* && "$ORIGIN_ADDRESS" != *$'\n'* ]] || die "Host/address contains a newline."

curl --noproxy '*' --fail --silent --show-error --head \
  --connect-timeout "$CONNECT_TIMEOUT" --max-time "$MAX_TIME" \
  "$PUBLIC_URL" >/dev/null
ok "Public hostname is reachable"

set +e
direct_status="$({ curl --noproxy '*' --silent --show-error --output /dev/null \
  --write-out '%{http_code}' --connect-timeout "$CONNECT_TIMEOUT" --max-time "$MAX_TIME" \
  --resolve "$PUBLIC_HOST:443:$ORIGIN_ADDRESS" "$PUBLIC_URL"; } 2>/dev/null)"
direct_exit=$?
set -e

if [[ "$direct_exit" -eq 0 && "$direct_status" =~ ^[1-5][0-9][0-9]$ ]]; then
  die "Origin bypass is still reachable with the production Host/SNI (HTTP $direct_status)."
fi

ok "Known origin address rejects the production Host/SNI"
