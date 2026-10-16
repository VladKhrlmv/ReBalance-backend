#!/usr/bin/env bash
# Restore ReBalance from a backup made by backup.sh.
#
#   sudo bash restore.sh --list                                   list local + remote backups
#   sudo bash restore.sh <db-dump.sql.gz> [images.tar.gz]         restore (asks for confirmation)
#   sudo bash restore.sh -y <db-dump.sql.gz> [images.tar.gz]      no prompt (scripts/drills)
#
# Arguments may be local paths, file names inside deploy/backups/, or object names in
# $BACKUP_REMOTE (they are downloaded into deploy/backups/ first).
#
# What it does:
#   - verifies the files (gzip integrity, complete dump, images/ in the archive)
#   - takes a safety dump of the CURRENT database: backups/pre-restore-<UTC>.sql.gz
#   - stops the backend, DROPS and recreates the "rebalance" database, imports the dump
#   - if an images archive is given: moves data/images aside to data/images.pre-restore-<UTC>
#     and extracts the archive in its place (owned by uid 10001)
#   - starts the backend again and waits until it is healthy
set -euo pipefail
umask 077

# shellcheck source-path=SCRIPTDIR source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

usage() {
  sed -n '2,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

assume_yes=0
list_only=0
args=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    -y | --yes) assume_yes=1 ;;
    -l | --list) list_only=1 ;;
    -h | --help) usage 0 ;;
    -*) echo "unknown option: $1" >&2; usage 2 ;;
    *) args+=("$1") ;;
  esac
  shift
done

require_root
load_backup_env
command -v docker >/dev/null || die "docker not found"
mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

if [[ $list_only -eq 1 ]]; then
  echo "== Local backups in $BACKUP_DIR"
  found="$(find "$BACKUP_DIR" -maxdepth 1 -type f \
    \( -name 'db-*.sql.gz' -o -name 'images-*.tar.gz' -o -name 'pre-restore-*.sql.gz' \) \
    -printf '%TY-%Tm-%Td %TH:%TM  %10s  %f\n' | sort)"
  echo "${found:-(none)}"
  if have_remote; then
    echo "== Remote backups in $BACKUP_REMOTE"
    rclone lsl "$BACKUP_REMOTE" --include 'db-*.sql.gz' --include 'images-*.tar.gz' | sort -k2,3
  else
    echo "== Remote: not configured (BACKUP_REMOTE / rclone)"
  fi
  exit 0
fi

[[ ${#args[@]} -ge 1 && ${#args[@]} -le 2 ]] || usage 2

# Resolve a backup argument to a local file: path -> backups/<name> -> download from remote.
resolve_backup() {
  local arg="$1" name
  name="$(basename "$arg")"
  if [[ -f $arg ]]; then
    printf '%s\n' "$(cd "$(dirname "$arg")" && pwd)/$name"
  elif [[ -f $BACKUP_DIR/$name ]]; then
    printf '%s\n' "$BACKUP_DIR/$name"
  elif have_remote; then
    log "downloading $name from $BACKUP_REMOTE" >&2
    rclone copyto "${rclone_opts[@]}" "$BACKUP_REMOTE/$name" "$BACKUP_DIR/$name" >&2 ||
      die "could not download $name from $BACKUP_REMOTE"
    printf '%s\n' "$BACKUP_DIR/$name"
  else
    die "backup file not found: $arg"
  fi
}

exec 8>/run/lock/rebalance-backup.lock
flock -n 8 || die "a backup/restore is already running"

dump_file="$(resolve_backup "${args[0]}")"
images_file=""
[[ ${#args[@]} -eq 2 ]] && images_file="$(resolve_backup "${args[1]}")"

log "verifying $dump_file"
case "$dump_file" in *.sql.gz) ;; *) die "first argument must be a .sql.gz database dump" ;; esac
verify_dump "$dump_file"
if [[ -n $images_file ]]; then
  log "verifying $images_file"
  verify_images_archive "$images_file"
fi

cd "$DEPLOY_DIR"
compose up -d --wait db

cat <<EOF

================================ RESTORE ================================
 Database dump : $dump_file
 Images archive: ${images_file:-"(none - receipt images are left unchanged)"}

 This will:
   * STOP the backend (the app is unavailable for a few minutes)
   * DROP the database "rebalance" and replace it with the dump
     (a safety dump of the current database is written to backups/ first)
EOF
[[ -n $images_file ]] && echo "   * replace data/images (current folder is kept as data/images.pre-restore-...)"
echo "========================================================================="

if [[ $assume_yes -ne 1 ]]; then
  [[ -t 0 ]] || die "not a terminal - pass -y to confirm non-interactively"
  read -r -p 'Type RESTORE to continue: ' answer
  [[ $answer == "RESTORE" ]] || die "aborted - nothing was changed"
fi

ts="$(date -u +%Y%m%dT%H%M%SZ)"
safety="$BACKUP_DIR/pre-restore-$ts.sql.gz"
log "safety dump of the current database -> $safety"
dump_database "$safety"

log "stopping backend"
compose stop backend

log "recreating database"
# shellcheck disable=SC2016  # expands inside the container
compose exec -T db sh -c '
  export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
  printf "DROP DATABASE IF EXISTS \`%s\`; CREATE DATABASE \`%s\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;\n" \
    "$MYSQL_DATABASE" "$MYSQL_DATABASE" | mysql --user=root'

log "importing $dump_file"
# shellcheck disable=SC2016
gzip -dc "$dump_file" | compose exec -T db sh -c '
  export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
  exec mysql --user=root --default-character-set=utf8mb4 "$MYSQL_DATABASE"'

if [[ -n $images_file ]]; then
  if [[ -d $IMAGES_DIR ]]; then
    log "moving current images to $IMAGES_DIR.pre-restore-$ts"
    mv "$IMAGES_DIR" "$IMAGES_DIR.pre-restore-$ts"
  fi
  log "extracting $images_file"
  mkdir -p "$DATA_DIR"
  tar -C "$DATA_DIR" -xzf "$images_file"
  chown -R "$APP_UID:$APP_GID" "$IMAGES_DIR"
  chmod 750 "$IMAGES_DIR"
fi

log "starting backend (waiting for it to become healthy)"
compose up -d --wait --wait-timeout 300

log "RESTORE COMPLETE"
log "  safety dump of the previous database: $safety"
[[ -n $images_file && -d $IMAGES_DIR.pre-restore-$ts ]] &&
  log "  previous images: $IMAGES_DIR.pre-restore-$ts (delete when no longer needed)"
exit 0
