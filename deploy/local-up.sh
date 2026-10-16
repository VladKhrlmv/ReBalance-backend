#!/usr/bin/env bash
# Build backend jar + SPA from local sources and start the full stack on http://localhost:8088.
#
# Usage (from anywhere):
#   bash deploy/local-up.sh                build everything, then (re)start the stack
#   SKIP_BUILD=1 bash deploy/local-up.sh   only rebuild images from existing target/ and build/
#   WEB_DIR=/path/to/ReBalanceWeb bash deploy/local-up.sh
#
# Requires: Docker (with compose v2.24+), JDK 21, Node.js + npm.
# Expects the frontend repo next to this one: ReBalance/ReBalanceWeb.
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(dirname "$DEPLOY_DIR")"
WEB_DIR="${WEB_DIR:-$BACKEND_DIR/../ReBalanceWeb}"

die() { echo "ERROR: $*" >&2; exit 1; }

command -v docker >/dev/null || die "docker not found"
docker compose version >/dev/null 2>&1 || die "docker compose plugin not found"
[[ -d $WEB_DIR ]] || die "frontend repo not found at $WEB_DIR (set WEB_DIR=...)"
WEB_DIR="$(cd "$WEB_DIR" && pwd)"

if [[ -z ${SKIP_BUILD:-} ]]; then
  echo ">>> Building backend jar ($BACKEND_DIR)"
  # `sh ./mvnw` works even if the executable bit is missing.
  (cd "$BACKEND_DIR" && sh ./mvnw -B -DskipTests package)

  echo ">>> Building SPA ($WEB_DIR)"
  (
    cd "$WEB_DIR"
    if [[ ! -d node_modules ]]; then npm ci; fi
    # Same build flags as CI: no inline runtime chunk (CSP script-src 'self'), no source maps.
    INLINE_RUNTIME_CHUNK=false GENERATE_SOURCEMAP=false npm run build
  )
fi

shopt -s nullglob
jars=("$BACKEND_DIR"/target/*.jar)
shopt -u nullglob
[[ ${#jars[@]} -gt 0 ]] || die "no jar in $BACKEND_DIR/target - run without SKIP_BUILD"
[[ -f $WEB_DIR/build/index.html ]] || die "no $WEB_DIR/build/index.html - run without SKIP_BUILD"

echo ">>> Starting stack"
cd "$DEPLOY_DIR"
export WEB_SRC_DIR="$WEB_DIR"
docker compose -f docker-compose.yml -f compose.local.yml --env-file .env.local up -d --build

cat <<EOF

ReBalance is starting on http://localhost:8088  (backend needs ~30-90 s on first start)
  status : docker compose -f docker-compose.yml -f compose.local.yml --env-file .env.local ps
  logs   : docker compose -f docker-compose.yml -f compose.local.yml --env-file .env.local logs -f backend
  stop   : docker compose -f docker-compose.yml -f compose.local.yml --env-file .env.local down
  reset  : docker compose -f docker-compose.yml -f compose.local.yml --env-file .env.local down -v
EOF
