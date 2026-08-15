#!/usr/bin/env bash
# Archives /opt/rojan/uploads (salon logo/cover/gallery/portfolio media,
# Salon Identity Foundation Phase A) to /opt/rojan/backups as a gzip'd tar,
# and prunes archives older than 14 days. Mirrors scripts/backup.sh's
# structure exactly, scoped to media instead of Postgres — a sibling
# script, not a replacement. Does not read, call, or modify backup.sh, and
# database backup behavior is untouched.
#
# Usage: ./scripts/backup-media.sh
# Cron example (daily at 03:05, after backup.sh's 03:00 run):
#   5 3 * * * /opt/rojan/backend/scripts/backup-media.sh >> /opt/rojan/logs/backup-media.log 2>&1
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

DATA_ROOT="${ROJAN_DATA_ROOT:-/opt/rojan}"
UPLOADS_DIR="$DATA_ROOT/uploads"
BACKUP_DIR="$DATA_ROOT/backups"

mkdir -p "$BACKUP_DIR"

if [ ! -d "$UPLOADS_DIR" ] || [ -z "$(ls -A "$UPLOADS_DIR" 2>/dev/null)" ]; then
  echo "==> $UPLOADS_DIR is missing or empty - nothing to back up yet, skipping."
  exit 0
fi

TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT_FILE="$BACKUP_DIR/rojan-media-$TIMESTAMP.tar.gz"

echo "==> Archiving $UPLOADS_DIR to $OUT_FILE"
tar -czf "$OUT_FILE" -C "$DATA_ROOT" uploads
echo "==> Backup complete: $OUT_FILE ($(du -h "$OUT_FILE" | cut -f1))"

# Same 14-day retention window as backup.sh's Postgres dumps, for the same
# reason (application-prod.yml's log rolling policy), not because the two
# scripts are technically related.
echo "==> Pruning media backups older than 14 days"
find "$BACKUP_DIR" -name 'rojan-media-*.tar.gz' -mtime +14 -print -delete
