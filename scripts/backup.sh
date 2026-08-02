#!/usr/bin/env bash
# Dumps the production Postgres database to /opt/rojan/backups (gzip'd
# SQL), and prunes archives older than 14 days. Intended to be run via
# cron (e.g. daily) as well as manually before any risky operation
# (upgrade, migration, rollback).
#
# Usage: ./scripts/backup.sh
# Cron example (daily at 03:00): 0 3 * * * /opt/rojan/backend/scripts/backup.sh >> /opt/rojan/logs/backup.log 2>&1
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

DATA_ROOT="${ROJAN_DATA_ROOT:-/opt/rojan}"
ENV_FILE="$REPO_ROOT/.env"
COMPOSE=(docker compose -f docker-compose.prod.yml --env-file "$ENV_FILE")
BACKUP_DIR="$DATA_ROOT/backups"

if [ ! -f "$ENV_FILE" ]; then
  echo "Missing $ENV_FILE - copy .env.example to .env and fill in real values first." >&2
  exit 1
fi

mkdir -p "$BACKUP_DIR"

# Only DB_NAME/DB_USERNAME are needed here, loaded from .env rather than
# hardcoded so this script never drifts from whatever the stack is
# actually configured with.
set -a
# shellcheck disable=SC1091
source "$ENV_FILE"
set +a

TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT_FILE="$BACKUP_DIR/rojan-postgres-$TIMESTAMP.sql.gz"

echo "==> Dumping Postgres ($DB_NAME) to $OUT_FILE"
"${COMPOSE[@]}" exec -T postgres pg_dump -U "$DB_USERNAME" "$DB_NAME" | gzip > "$OUT_FILE"
echo "==> Backup complete: $OUT_FILE ($(du -h "$OUT_FILE" | cut -f1))"

# Retention window matches application-prod.yml's log rolling policy
# (logging.logback.rollingpolicy.max-history: 14) for consistency, not
# because they're technically related.
echo "==> Pruning backups older than 14 days"
find "$BACKUP_DIR" -name 'rojan-postgres-*.sql.gz' -mtime +14 -print -delete
