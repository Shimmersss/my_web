#!/usr/bin/env bash
set -euo pipefail

export OPENCLAW_NO_RESPAWN="${OPENCLAW_NO_RESPAWN:-1}"
export NODE_COMPILE_CACHE="${NODE_COMPILE_CACHE:-/var/tmp/openclaw-compile-cache}"
mkdir -p "$NODE_COMPILE_CACHE" 2>/dev/null || true

OPENCLAW_BIN="${OPENCLAW_BIN:-openclaw}"
original_args=("$@")

if [[ "${1:-}" == "gateway" ]]; then
  shift
  if [[ "${1:-}" == "--json" && "${2:-}" == "--params" && -n "${3:-}" ]]; then
    params_json="$3"
    method="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1]).get("method",""))' "$params_json")"
    params="$(python3 -c 'import json,sys; print(json.dumps(json.loads(sys.argv[1]).get("params",{}), ensure_ascii=False))' "$params_json")"
    if [[ -z "$method" ]]; then
      echo "openclaw_compat: missing gateway method" >&2
      exit 2
    fi
    exec "$OPENCLAW_BIN" gateway call "$method" --json --params "$params"
  fi
fi

exec "$OPENCLAW_BIN" "${original_args[@]}"
