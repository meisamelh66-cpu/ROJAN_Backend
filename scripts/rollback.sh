#!/usr/bin/env bash
# Roll back the ROJAN backend application code to the previously deployed
# commit (as recorded by deploy.sh). Rebuilds and restarts only the `app`
# service - Postgres/Redis/Nginx/Certbot are left running.
#
# Does NOT touch the database schema. Flyway migrations are forward-only;
# if the deploy being rolled back introduced a migration incompatible with
# the previous commit, restore Postgres from a matching backup.sh archive
# instead of (or in addition to) running this script - see DEPLOYMENT.md's
# restore steps.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

DATA_ROOT="${ROJAN_DATA_ROOT:-/opt/rojan}"
ENV_FILE="$REPO_ROOT/.env"
COMPOSE=(docker compose -f docker-compose.prod.yml --env-file "$ENV_FILE")

ROLLBACK_FILE="$DATA_ROOT/backups/last-deployed-commit.txt"
PREVIOUS_FILE="$ROLLBACK_FILE.previous"

if [ ! -f "$PREVIOUS_FILE" ]; then
  echo "No previous deployment recorded at $PREVIOUS_FILE - nothing to roll back to." >&2
  echo "(This file is written by deploy.sh starting from its second run.)" >&2
  exit 1
fi

PREVIOUS_COMMIT="$(cat "$PREVIOUS_FILE")"
CURRENT_COMMIT="$(git rev-parse HEAD)"

if [ "$PREVIOUS_COMMIT" = "$CURRENT_COMMIT" ]; then
  echo "Already at $PREVIOUS_COMMIT - nothing to do." >&2
  exit 1
fi

echo "==> Rolling back application code: $CURRENT_COMMIT -> $PREVIOUS_COMMIT"
git checkout "$PREVIOUS_COMMIT"

echo "==> Rebuilding and restarting the app on the previous commit"
"${COMPOSE[@]}" build app
"${COMPOSE[@]}" up -d app

echo "==> Waiting for the app to report healthy (up to 2.5 min)"
container_id="$("${COMPOSE[@]}" ps -q app)"
for _ in $(seq 1 30); do
  status="$(docker inspect -f '{{.State.Health.Status}}' "$container_id" 2>/dev/null || echo unknown)"
  if [ "$status" = "healthy" ]; then
    echo "==> Rollback complete, app is healthy at $PREVIOUS_COMMIT."
    exit 0
  fi
  sleep 5
done

echo "App did not become healthy after rollback - check: docker compose -f docker-compose.prod.yml logs app" >&2
exit 1
