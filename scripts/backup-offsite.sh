#!/usr/bin/env bash
# Encrypts the most recent local Postgres backup (produced by
# scripts/backup.sh) and uploads it to an off-site storage remote via
# rclone. Does NOT modify, call, or replace backup.sh — run this AFTER
# backup.sh, as a separate cron entry, so a fresh local dump always
# exists before this script looks for one.
#
# Usage: ./scripts/backup-offsite.sh
# Cron example (daily at 03:15, 15 min after backup.sh's 03:00 run):
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

LATEST_BACKUP="$(find "$BACKUP_DIR" -maxdepth 1 -name 'rojan-postgres-*.sql.gz' -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -n1 | cut -d' ' -f2-)"

if [ -z "$LATEST_BACKUP" ]; then
  echo "No local backup found in $BACKUP_DIR - run scripts/backup.sh first." >&2
  exit 1
fi

ENCRYPTED_FILE="${LATEST_BACKUP}.gpg"

echo "==> Encrypting $(basename "$LATEST_BACKUP")"
# Passphrase is piped into gpg's stdin (--passphrase-fd 0) rather than
# passed as a --passphrase CLI argument — command-line arguments are
# visible to any other process able to read /proc/<pid>/cmdline or run
# `ps aux` for the duration of the call; a stdin pipe never appears
# there. printf (not echo) avoids any risk of the passphrase being
# misinterpreted as printf/echo flags if it happened to start with '-'.
printf '%s' "$BACKUP_ENCRYPTION_PASSPHRASE" | gpg --batch --yes --passphrase-fd 0 \
    --symmetric --cipher-algo AES256 \
    --output "$ENCRYPTED_FILE" "$LATEST_BACKUP"

echo "==> Uploading $(basename "$ENCRYPTED_FILE") to $RCLONE_REMOTE"
rclone copy "$ENCRYPTED_FILE" "$RCLONE_REMOTE" --checksum

# The unencrypted local backup and its own 14-day retention remain
# entirely backup.sh's responsibility (untouched by this script) — this
# only cleans up the transient encrypted copy created above, so a stale
# .gpg file never lingers in the same directory backup.sh prunes.
echo "==> Removing local encrypted copy (upload succeeded, no need to keep a second local copy)"
rm -f "$ENCRYPTED_FILE"

echo "==> Off-site backup complete: $(basename "$LATEST_BACKUP") -> $RCLONE_REMOTE"
