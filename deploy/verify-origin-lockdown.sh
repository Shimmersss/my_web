#!/usr/bin/env bash
# Verify that the public hostname works while a known origin address cannot be
# reached with the production Host/SNI. Keep ORIGIN_ADDRESS outside Git.

set -euo pipefail

PUBLIC_HOST="${PUBLIC_HOST:-shimmer.help}"
PUBLIC_URL="${PUBLIC_URL:-https://$PUBLIC_HOST/}"
ORIGIN_ADDRESS="${ORIGIN_ADDRESS:-}"
CONNECT_TIMEOUT="${CONNECT_TIMEOUT:-5}"
MAX_TIME="${MAX_TIME:-12}"

die() { printf '[x] %s\n' "$*" >&2; exit 1; }
ok() { printf '[ok] %s\n' "$*"; }

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
