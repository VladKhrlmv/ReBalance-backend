#!/usr/bin/env bash
# Server-side deploy step, run on the VM by the GitHub Actions deploy workflows over SSH
# (or by hand):
#
#   bash /opt/rebalance/app/deploy/deploy.sh backend   # git pull + pull backend image + up
#   bash /opt/rebalance/app/deploy/deploy.sh web       # pull web image + recreate web
#
# Runs as the deploy user (member of the "docker" group). Concurrent deploys from the two
# repositories are serialised with a lock file. Fails (non-zero) if the stack does not
# become healthy, so the GitHub job turns red.
#
# Optional hardening: the target may also come from SSH_ORIGINAL_COMMAND, so the deploy key
# can be restricted in ~/.ssh/authorized_keys with
#   restrict,command="bash /opt/rebalance/app/deploy/deploy.sh" ssh-ed25519 AAAA... github-deploy
set -euo pipefail

target="${1:-}"
if [[ -z $target && -n ${SSH_ORIGINAL_COMMAND:-} ]]; then
  target="${SSH_ORIGINAL_COMMAND##* }"
fi
case "$target" in
  backend | web) ;;
  *) echo "usage: deploy.sh backend|web" >&2; exit 2 ;;
esac

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(dirname "$DEPLOY_DIR")"
WAIT_TIMEOUT="${WAIT_TIMEOUT:-300}"

log() { printf '[deploy %s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }

# One deploy at a time (fd 9 stays open, and locked, across the re-exec below).
if [[ -z ${REBALANCE_DEPLOY_LOCKED:-} ]]; then
  exec 9>"${TMPDIR:-/tmp}/rebalance-deploy.lock"
  log "waiting for deploy lock"
  flock -w 900 9 || { echo "another deploy is still running" >&2; exit 1; }
  export REBALANCE_DEPLOY_LOCKED=1
fi

cd "$DEPLOY_DIR"
[[ -f .env ]] || { echo "$DEPLOY_DIR/.env is missing - run setup-vm.sh first" >&2; exit 1; }

if [[ $target == backend && -z ${REBALANCE_DEPLOY_PULLED:-} ]]; then
  log "updating repository checkout in $APP_DIR"
  git -C "$APP_DIR" pull --ff-only
  # Continue with the (possibly updated) version of this script.
  export REBALANCE_DEPLOY_PULLED=1
  exec bash "$DEPLOY_DIR/deploy.sh" "$target"
fi

case "$target" in
  backend)
    log "pulling backend image"
    docker compose pull backend
    log "starting stack"
    docker compose up -d --remove-orphans --wait --wait-timeout "$WAIT_TIMEOUT"

    # The Caddyfile is a single-file bind mount; git replaces the file (new inode), so a
    # running container keeps seeing the old content. Recreate web if it differs.
    if [[ -n $(docker compose ps --status running -q web) ]] &&
      ! docker compose exec -T web cat /etc/caddy/Caddyfile | cmp -s - Caddyfile; then
      log "Caddyfile changed - recreating web"
      docker compose up -d --no-deps --force-recreate --wait --wait-timeout "$WAIT_TIMEOUT" web
    fi
    ;;
  web)
    log "pulling web image"
    docker compose pull web
    log "starting web"
    docker compose up -d --wait --wait-timeout "$WAIT_TIMEOUT" web
    ;;
esac

log "removing dangling images"
docker image prune -f >/dev/null

docker compose ps
log "deploy of '$target' finished"
