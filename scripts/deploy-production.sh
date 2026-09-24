#!/usr/bin/env bash
# Runs ON the VPS (invoked over SSH by .github/workflows/backend-production-deploy.yml, or
# manually). Consumes a pre-built image already streamed onto this host and brings it up via
# docker-compose.prod.yml - never rebuilds locally. Mirrors ROJAN_Web's
# scripts/deploy-production.sh pattern; adapted for this repo's layout (docker-compose.prod.yml
# and .env live at the repo root here, not under a deploy/<app>/ subdirectory) and for the
# backend's own real container HEALTHCHECK (poll `docker inspect`'s Health.Status, the same
# mechanism scripts/deploy.sh and scripts/rollback.sh already use - not a separate manual probe).
#
# Usage: scripts/deploy-production.sh <image-tag>
#   <image-tag>  a tag already pushed to ghcr.io/<owner>/rojan-backend (typically a commit SHA)
set -euo pipefail

IMAGE_TAG="${1:?Usage: deploy-production.sh <image-tag>}"
REGISTRY_IMAGE="ghcr.io/meisamelh66-cpu/rojan-backend"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

DATA_ROOT="${ROJAN_DATA_ROOT:-/opt/rojan}"
ENV_FILE="$REPO_ROOT/.env"
COMPOSE=(docker compose -f docker-compose.prod.yml --env-file "$ENV_FILE")

# Deliberately a different filename from scripts/deploy.sh's own
# $DATA_ROOT/backups/last-deployed-commit.txt - that file tracks a *git commit* for the
# local-build rollback path (scripts/rollback.sh); this one tracks an *image tag* for this
# preloaded-image path (scripts/rollback-production.sh). The two rollback mechanisms are
# intentionally kept separate so neither can silently overwrite or misread the other's state.
STATE_FILE="$DATA_ROOT/backups/.last-deployed-image-tag"

if [ ! -f "$ENV_FILE" ]; then
  echo "Missing $ENV_FILE - copy .env.example to .env and fill in real values first." >&2
  exit 1
fi

# Record what's currently running before switching, so a bad deploy can be undone.
if [ -f "$STATE_FILE" ]; then
  cp "$STATE_FILE" "$STATE_FILE.previous"
fi

export BACKEND_IMAGE="$REGISTRY_IMAGE:$IMAGE_TAG"
echo "Deploying $BACKEND_IMAGE ..."

if docker image inspect "$BACKEND_IMAGE" >/dev/null 2>&1; then
  echo "STEP: DOCKER PULL (skipped - $BACKEND_IMAGE already loaded locally)"
else
  # Retry-guarded, same reasoning as the website's own deploy-production.sh: a manual run or
  # scripts/rollback-production.sh restoring a tag that's since been pruned locally would need to
  # pull it fresh, and this VPS's registry access is confirmed unreliable (2026-09-24 investigation).
  echo "STEP: DOCKER PULL"
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
# --no-build is the one non-negotiable flag here: with both `image:` and `build:` set on the
# `app` service (docker-compose.prod.yml), Compose's own default behavior is to build the image
# if it isn't present locally - --no-build turns that into a hard failure instead, so a broken
# image stream can never silently fall through into the same Docker Hub build path this whole
# script exists to avoid.
"${COMPOSE[@]}" up -d --no-build --force-recreate app

echo "STEP: HEALTH CHECK"
container_id="$("${COMPOSE[@]}" ps -q app)"
HEALTHY=false
for _ in $(seq 1 30); do
  status="$(docker inspect -f '{{.State.Health.Status}}' "$container_id" 2>/dev/null || echo unknown)"
  if [ "$status" = "healthy" ]; then
    HEALTHY=true
    break
  fi
  sleep 5
done

if [ "$HEALTHY" != "true" ]; then
  echo "Health check FAILED for $BACKEND_IMAGE after ~2.5 min." >&2
  # Same automatic-recovery shape as the website's deploy-production.sh: a failed deploy must
  # never just leave the broken container as the one serving api.rojanai.ir until a human
  # notices. STATE_FILE.previous was already written above (the tag that was healthy immediately
  # before this attempt), so - if one exists - roll back to it automatically right here.
  if [ -f "$STATE_FILE.previous" ]; then
    echo "Rolling back automatically to the last known-good image..." >&2
    SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    exec "$SCRIPT_DIR/rollback-production.sh"
  fi
  echo "No previous deployment recorded - nothing to automatically roll back to." >&2
  echo "Database note: Flyway migrations are forward-only. If this deploy applied a new migration," >&2
  echo "reverting the app container alone is not sufficient - restore Postgres from a matching" >&2
  echo "backup.sh archive as well. This script never attempts that automatically." >&2
  exit 1
fi

# Same nginx stale-upstream-IP fix scripts/deploy.sh and scripts/rollback.sh already carry: a
# recreated container gets a new Docker network IP, and nginx won't notice until restarted.
echo "==> Restarting nginx so it re-resolves the app container's new address"
"${COMPOSE[@]}" restart nginx

# Written only now, after the health check passes - a deploy whose container came up but then
# failed its health check must never leave this file claiming that tag as "last deployed".
echo "$IMAGE_TAG" > "$STATE_FILE"

echo "Deployed $BACKEND_IMAGE successfully - health check passed."
