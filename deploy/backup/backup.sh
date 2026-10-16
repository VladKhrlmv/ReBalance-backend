#!/usr/bin/env bash
# Nightly backup of ReBalance (run by rebalance-backup.timer, or by hand):
#
#   sudo bash /opt/rebalance/app/deploy/backup/backup.sh
#
#  1. mysqldump --single-transaction --routines of the "rebalance" database -> db-<UTC>.sql.gz
#  2. tar.gz of deploy/data/images (receipt images)                         -> images-<UTC>.tar.gz
#  3. upload both to $BACKUP_REMOTE with rclone (OCI Object Storage, S3-compatible API)
#  4. delete backups older than $BACKUP_RETENTION_DAYS days, locally and remotely
#
# Settings come from deploy/.env (BACKUP_*, RCLONE_CONFIG_*). Any failure exits non-zero
# (systemd marks the unit failed; BACKUP_PING_URL gets a /fail ping). A missing
# BACKUP_REMOTE is a failure too: the local copy is made, but nothing left the VM.
set -euo pipefail
umask 077

# shellcheck source-path=SCRIPTDIR source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

ping_url() {
  [[ -n ${BACKUP_PING_URL:-} ]] || return 0
  curl -fsS -m 10 --retry 3 -o /dev/null "$1" || warn "could not reach $1"
}

on_exit() {
  local rc=$?
  if [[ $rc -ne 0 ]]; then
    log "BACKUP FAILED (exit code $rc)" >&2
    ping_url "${BACKUP_PING_URL:-}/fail"
  fi
  rm -f "${BACKUP_DIR:-/nonexistent}"/*.partial 2>/dev/null || true
}
trap on_exit EXIT

require_root
load_backup_env
command -v docker >/dev/null || die "docker not found"

exec 8>/run/lock/rebalance-backup.lock
flock -n 8 || die "another backup/restore is running"

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"
ts="$(date -u +%Y%m%dT%H%M%SZ)"
db_file="$BACKUP_DIR/db-$ts.sql.gz"
img_file="$BACKUP_DIR/images-$ts.tar.gz"

log "dumping database -> $db_file"
dump_database "$db_file"
log "database dump OK ($(du -h "$db_file" | cut -f1))"

[[ -d $IMAGES_DIR ]] || die "$IMAGES_DIR does not exist"
log "archiving receipt images -> $img_file"
tar -C "$DATA_DIR" -czf "$img_file.partial" images
verify_images_archive "$img_file.partial"
mv -f "$img_file.partial" "$img_file"
log "images archive OK ($(du -h "$img_file" | cut -f1))"

uploaded=0
if [[ -z $BACKUP_REMOTE ]]; then
  warn "BACKUP_REMOTE is empty - backup kept ONLY on this VM"
elif ! command -v rclone >/dev/null; then
  warn "rclone is not installed - backup kept ONLY on this VM"
else
  for f in "$db_file" "$img_file"; do
    log "uploading $(basename "$f") -> $BACKUP_REMOTE"
    rclone copyto "${rclone_opts[@]}" "$f" "$BACKUP_REMOTE/$(basename "$f")"
  done
  log "pruning remote backups older than ${BACKUP_RETENTION_DAYS}d"
  rclone delete "${rclone_opts[@]}" "$BACKUP_REMOTE" --min-age "${BACKUP_RETENTION_DAYS}d" \
    --include 'db-*.sql.gz' --include 'images-*.tar.gz'
  uploaded=1
fi

log "pruning local backups older than ${BACKUP_RETENTION_DAYS}d"
find "$BACKUP_DIR" -maxdepth 1 -type f \
  \( -name 'db-*.sql.gz' -o -name 'images-*.tar.gz' -o -name 'pre-restore-*.sql.gz' \) \
  -mtime +"$((BACKUP_RETENTION_DAYS - 1))" -print -delete

[[ $uploaded -eq 1 ]] || die "backup was NOT uploaded off the VM (configure BACKUP_REMOTE + rclone in .env)"

ping_url "$BACKUP_PING_URL"
log "backup finished: $(basename "$db_file"), $(basename "$img_file")"
