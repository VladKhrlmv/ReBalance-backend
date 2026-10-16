#!/usr/bin/env bash
# Shared helpers for backup.sh and restore.sh (sourced, not executed).
# shellcheck disable=SC2034  # variables are used by the sourcing scripts

BACKUP_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(dirname "$BACKUP_LIB_DIR")"
ENV_FILE="$DEPLOY_DIR/.env"
DATA_DIR="$DEPLOY_DIR/data"
IMAGES_DIR="$DATA_DIR/images"
APP_UID=10001
APP_GID=10001

log() { printf '[%s] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
warn() { log "WARNING: $*" >&2; }
die() { log "ERROR: $*" >&2; exit 1; }

require_root() {
  [[ $EUID -eq 0 ]] || die "run as root (sudo bash $0 ...)"
}

# Export BACKUP_* and RCLONE_* settings from .env WITHOUT sourcing it (.env contains values
# such as DB_URL with '&' that a shell would misinterpret). Variables already present in the
# environment win, so e.g. `BACKUP_REMOTE= backup.sh` overrides the file.
load_backup_env() {
  [[ -f $ENV_FILE ]] || die "$ENV_FILE not found"
  local line key value
  local -A from_file=()
  while IFS= read -r line || [[ -n $line ]]; do
    line="${line%$'\r'}"
    [[ $line =~ ^(BACKUP_[A-Z0-9_]+|RCLONE_[A-Z0-9_]+)=(.*)$ ]] || continue
    key="${BASH_REMATCH[1]}"
    value="${BASH_REMATCH[2]}"
    if [[ $value =~ ^\"(.*)\"[[:space:]]*$ || $value =~ ^\'(.*)\'[[:space:]]*$ ]]; then
      value="${BASH_REMATCH[1]}"
    else
      value="${value%%[[:space:]]#*}"                        # inline comment
      value="${value%"${value##*[![:space:]]}"}"            # trailing whitespace
    fi
    from_file[$key]="$value" # last occurrence wins, like docker compose
  done <"$ENV_FILE"
  for key in "${!from_file[@]}"; do
    if [[ -z ${!key+x} ]]; then
      export "$key=${from_file[$key]}"
    fi
  done

  BACKUP_DIR="${BACKUP_DIR:-$DEPLOY_DIR/backups}"
  BACKUP_RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"
  BACKUP_REMOTE="${BACKUP_REMOTE:-}"
  BACKUP_PING_URL="${BACKUP_PING_URL:-}"
  [[ $BACKUP_RETENTION_DAYS =~ ^[1-9][0-9]*$ ]] || die "BACKUP_RETENTION_DAYS must be a positive integer"
}

compose() {
  docker compose --project-directory "$DEPLOY_DIR" -f "$DEPLOY_DIR/docker-compose.yml" "$@"
}

require_db_running() {
  [[ -n $(compose ps --status running -q db) ]] || die "db container is not running (cd $DEPLOY_DIR && docker compose up -d db)"
}

# Logical dump of the application database (as MySQL root, inside the db container), gzip'ed.
# Writes to "$1.partial" first and verifies the dump is complete before renaming.
dump_database() {
  local out="$1" tmp="$1.partial"
  require_db_running
  # shellcheck disable=SC2016  # $MYSQL_* expand inside the container
  compose exec -T db sh -c '
    export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
    exec mysqldump --user=root --single-transaction --routines --triggers --events \
      --no-tablespaces --set-gtid-purged=OFF --hex-blob --default-character-set=utf8mb4 \
      --databases "$MYSQL_DATABASE"' | gzip -c >"$tmp"
  verify_dump "$tmp"
  mv -f "$tmp" "$out"
}

verify_dump() {
  local file="$1"
  gzip -t "$file" || die "$file is not a valid gzip file"
  gzip -dc "$file" | tail -n 1 | grep -q '^-- Dump completed' ||
    die "$file is incomplete (no '-- Dump completed' trailer)"
}

verify_images_archive() {
  local file="$1"
  tar -tzf "$file" >/dev/null || die "$file is not a valid .tar.gz"
  tar -tzf "$file" | head -n 1 | grep -q '^images/' || die "$file does not contain an images/ directory"
}

have_remote() {
  [[ -n $BACKUP_REMOTE ]] && command -v rclone >/dev/null
}

rclone_opts=(--retries 5 --low-level-retries 10 --stats-one-line --stats 0)
