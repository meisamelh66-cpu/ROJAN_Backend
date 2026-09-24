#!/usr/bin/env bash
# Rolls back the ROJAN backend to the previously-deployed *image tag* (as recorded by
# scripts/deploy-production.sh in $DATA_ROOT/backups/.last-deployed-image-tag.previous).
# Consumes an already-loaded/pullable image - never rebuilds locally. This is the
# preloaded-image counterpart to scripts/rollback.sh (which rolls back a *git commit* and
# rebuilds locally); the two are intentionally separate, matching deploy.sh/deploy-production.sh's
# own split.
#
# Does NOT touch the database schema. Flyway migrations are forward-only - if the deploy being
# rolled back introduced a migration incompatible with the previous image's code, restore
# Postgres from a matching backup.sh archive instead of (or in addition to) running this script -
# see DEPLOYMENT.md's restore steps. This script never attempts an automatic database rollback.
set -euo pipefail

REGISTRY_IMAGE="ghcr.io/meisamelh66-cpu/rojan-backend"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

DATA_ROOT="${ROJAN_DATA_ROOT:-/opt/rojan}"
ENV_FILE="$REPO_ROOT/.env"
COMPOSE=(docker compose -f docker-compose.prod.yml --env-file "$ENV_FILE")

STATE_FILE="$DATA_ROOT/backups/.last-deployed-image-tag"
PREVIOUS_FILE="$STATE_FILE.previous"

if [ ! -f "$PREVIOUS_FILE" ]; then
  echo "No previous image-tag deployment recorded at $PREVIOUS_FILE - nothing to roll back to." >&2
  echo "(This file is written by deploy-production.sh starting from its second successful run.)" >&2
  exit 1
fi

PREVIOUS_TAG="$(cat "$PREVIOUS_FILE")"
CURRENT_TAG="$(cat "$STATE_FILE" 2>/dev/null || echo "(unknown)")"

if [ "$PREVIOUS_TAG" = "$CURRENT_TAG" ]; then
  echo "Already at $PREVIOUS_TAG - nothing to do." >&2
  exit 1
fi

export BACKEND_IMAGE="$REGISTRY_IMAGE:$PREVIOUS_TAG"
echo "==> Rolling back application image: $CURRENT_TAG -> $PREVIOUS_TAG"

if ! docker image inspect "$BACKEND_IMAGE" >/dev/null 2>&1; then
  echo "STEP: DOCKER PULL ($BACKEND_IMAGE not present locally)"
  PULL_ATTEMPTS=5
  PULL_TIMEOUT_SECONDS=60
  PULL_RETRY_DELAY_SECONDS=15
  pull_succeeded=false
  for attempt in $(seq 1 "$PULL_ATTEMPTS"); do
    echo "docker compose pull (attempt $attempt/$PULL_ATTEMPTS, ${PULL_TIMEOUT_SECONDS}s timeout)"
    if timeout "${PULL_TIMEOUT_SECONDS}s" env BACKEND_IMAGE="$BACKEND_IMAGE" "${COMPOSE[@]}" pull app; then
      pull_succeeded=true
      break
    fi
    echo "docker compose pull attempt $attempt failed or timed out."
    if [ "$attempt" -lt "$PULL_ATTEMPTS" ]; then
      echo "retrying in ${PULL_RETRY_DELAY_SECONDS}s..."
      sleep "$PULL_RETRY_DELAY_SECONDS"
    fi
  done
  if [ "$pull_succeeded" != "true" ]; then
    echo "docker compose pull failed after $PULL_ATTEMPTS attempts for $BACKEND_IMAGE." >&2
    exit 1
  fi
fi

echo "STEP: CONTAINER START"
# --no-build for the same reason as deploy-production.sh: never let a missing/failed image
# fall through into a local Docker Hub build.
"${COMPOSE[@]}" up -d --no-build --force-recreate app

echo "STEP: HEALTH CHECK"
container_id="$("${COMPOSE[@]}" ps -q app)"
for _ in $(seq 1 30); do
  status="$(docker inspect -f '{{.State.Health.Status}}' "$container_id" 2>/dev/null || echo unknown)"
  if [ "$status" = "healthy" ]; then
    echo "==> Rollback complete, app is healthy at $PREVIOUS_TAG."
    echo "==> Restarting nginx so it re-resolves the app container's new address"
    "${COMPOSE[@]}" restart nginx
    echo "$PREVIOUS_TAG" > "$STATE_FILE"
    exit 0
  fi
  sleep 5
done

echo "App did not become healthy after rollback - check: docker compose -f docker-compose.prod.yml logs app" >&2
exit 1
