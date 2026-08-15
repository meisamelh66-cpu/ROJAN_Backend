#!/usr/bin/env bash
# Encrypts the most recent local Postgres backup (produced by
# scripts/backup.sh) and uploads it to an off-site storage remote via
# rclone. Also picks up the most recent salon-media backup (produced by
# scripts/backup-media.sh), if one exists, and uploads it the same way —
# the pg_dump upload below is unconditional (fails loudly if missing, same
# as before this was added); the media upload is best-effort and skips
# quietly if scripts/backup-media.sh hasn't produced an archive yet, so
# this script's original DB-only behavior is unchanged for anyone not
# using the media feature. Does NOT modify, call, or replace backup.sh or
# backup-media.sh — run this AFTER both, as a separate cron entry, so
# fresh local archives always exist before this script looks for them.
#
# Usage: ./scripts/backup-offsite.sh
# Cron example (daily at 03:15, 15 min after backup.sh/backup-media.sh's
# 03:00/03:05 runs):
#   15 3 * * * /opt/rojan/backend/scripts/backup-offsite.sh >> /opt/rojan/logs/backup-offsite.log 2>&1
#
# Prerequisites (not installed/configured by this script):
#   - gpg (preinstalled on Ubuntu 24.04) — used for symmetric encryption
#     so the off-site copy is protected independently of whatever
#     transport TLS or access control the storage provider itself offers.
#   - rclone, installed (https://rclone.org/install/) and configured
#     (`rclone config`) with a remote matching RCLONE_REMOTE below.
#     rclone is used specifically because it supports effectively any
#     S3-compatible or native object-storage backend through one
#     interface — this script does not hardcode a specific off-site
#     vendor, since that choice hasn't been made yet (see
#     ROJAN_Production_Readiness_Gap_Closure_Plan_v1.md §2).
#   - BACKUP_ENCRYPTION_PASSPHRASE and RCLONE_REMOTE set in .env (see
#     .env.example) — both required, same fail-loud posture as
#     JWT_SECRET/SMS_API_KEY elsewhere in this project. Neither value is
#     ever logged or printed by this script.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

DATA_ROOT="${ROJAN_DATA_ROOT:-/opt/rojan}"
ENV_FILE="$REPO_ROOT/.env"
BACKUP_DIR="$DATA_ROOT/backups"

if [ ! -f "$ENV_FILE" ]; then
  echo "Missing $ENV_FILE - copy .env.example to .env and fill in real values first." >&2
  exit 1
fi

set -a
# shellcheck disable=SC1091
source "$ENV_FILE"
set +a

: "${BACKUP_ENCRYPTION_PASSPHRASE:?BACKUP_ENCRYPTION_PASSPHRASE must be set in .env - see .env.example}"
: "${RCLONE_REMOTE:?RCLONE_REMOTE must be set in .env (e.g. offsite:rojan-backups/) - see .env.example}"

if ! command -v gpg >/dev/null 2>&1; then
  echo "gpg is not installed - required to encrypt the backup before upload." >&2
  exit 1
fi

if ! command -v rclone >/dev/null 2>&1; then
  echo "rclone is not installed - see https://rclone.org/install/ and run 'rclone config' to set up the '${RCLONE_REMOTE%%:*}' remote first." >&2
  exit 1
fi

# Encrypts and uploads one local archive, then removes the transient
# encrypted copy. The unencrypted local archive and its own 14-day
# retention remain entirely backup.sh's/backup-media.sh's responsibility
# (untouched by this script).
encrypt_and_upload() {
  local archive="$1"
  local encrypted="${archive}.gpg"

  echo "==> Encrypting $(basename "$archive")"
  # Passphrase is piped into gpg's stdin (--passphrase-fd 0) rather than
  # passed as a --passphrase CLI argument — command-line arguments are
  # visible to any other process able to read /proc/<pid>/cmdline or run
  # `ps aux` for the duration of the call; a stdin pipe never appears
  # there. printf (not echo) avoids any risk of the passphrase being
  # misinterpreted as printf/echo flags if it happened to start with '-'.
  printf '%s' "$BACKUP_ENCRYPTION_PASSPHRASE" | gpg --batch --yes --passphrase-fd 0 \
      --symmetric --cipher-algo AES256 \
      --output "$encrypted" "$archive"

  echo "==> Uploading $(basename "$encrypted") to $RCLONE_REMOTE"
  rclone copy "$encrypted" "$RCLONE_REMOTE" --checksum

  echo "==> Removing local encrypted copy (upload succeeded, no need to keep a second local copy)"
  rm -f "$encrypted"

  echo "==> Off-site backup complete: $(basename "$archive") -> $RCLONE_REMOTE"
}

LATEST_DB_BACKUP="$(find "$BACKUP_DIR" -maxdepth 1 -name 'rojan-postgres-*.sql.gz' -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -n1 | cut -d' ' -f2-)"

if [ -z "$LATEST_DB_BACKUP" ]; then
  echo "No local backup found in $BACKUP_DIR - run scripts/backup.sh first." >&2
  exit 1
fi

encrypt_and_upload "$LATEST_DB_BACKUP"

# Media off-site coverage (Salon Identity Foundation Phase A pre-merge
# hardening). Best-effort/optional, unlike the DB check above: a fresh
# install with no salon media uploaded yet has nothing for
# backup-media.sh to have produced, and that must not fail this script's
# unconditional, higher-priority DB backup.
LATEST_MEDIA_BACKUP="$(find "$BACKUP_DIR" -maxdepth 1 -name 'rojan-media-*.tar.gz' -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -n1 | cut -d' ' -f2-)"

if [ -z "$LATEST_MEDIA_BACKUP" ]; then
  echo "==> No local media backup found in $BACKUP_DIR yet (run scripts/backup-media.sh) - skipping media off-site upload."
else
  encrypt_and_upload "$LATEST_MEDIA_BACKUP"
fi
