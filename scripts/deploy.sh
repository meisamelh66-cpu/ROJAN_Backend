#!/usr/bin/env bash
# Deploy or upgrade the ROJAN backend production stack.
#
# Expects to run from a checkout of this repo at /opt/rojan/backend (or
# wherever ROJAN_DATA_ROOT's sibling "backend" checkout lives), with
# `.env` already populated (see .env.example) and the target VPS already
# provisioned with Docker + the Compose plugin (DEPLOYMENT.md's
# prerequisites). Needs root (or a user in the `docker` group *and* able
# to chown into ROJAN_DATA_ROOT) — see the chown step below.
#
# What this does NOT do: git pull/checkout a specific ref for you (build
# from whatever's already checked out — the operator controls that
# explicitly, on purpose), obtain the first Let's Encrypt certificate
# (see docker/nginx/init-letsencrypt.sh, a separate one-time step), or
# touch the database schema (Flyway does that automatically on app boot).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

DATA_ROOT="${ROJAN_DATA_ROOT:-/opt/rojan}"
ENV_FILE="$REPO_ROOT/.env"
COMPOSE=(docker compose -f docker-compose.prod.yml --env-file "$ENV_FILE")

if [ ! -f "$ENV_FILE" ]; then
  echo "Missing $ENV_FILE - copy .env.example to .env and fill in real values first." >&2
  exit 1
fi

echo "==> Ensuring persistent host directories exist with correct ownership"
mkdir -p "$DATA_ROOT"/postgres "$DATA_ROOT"/redis "$DATA_ROOT"/logs "$DATA_ROOT"/backups \
         "$DATA_ROOT"/nginx/webroot "$DATA_ROOT"/nginx/letsencrypt "$DATA_ROOT"/nginx/logs
# this repo's Dockerfile runs the app as uid/gid 10001. Bind mounts take on
# the HOST directory's ownership as-is - the official images' own
# chown-on-first-boot behavior only kicks in for Docker-managed named
# volumes, not host bind mounts - so this has to be set explicitly here.
#
# Previously hardcoded to 999:999 for both, on the (wrong) assumption that
# postgres:16-alpine and redis:7-alpine both run as uid/gid 999. Confirmed
# via `docker exec backend-postgres-1 id postgres` that Postgres's real uid
# is 70 - the 999 guess broke Postgres's ability to read its own data files
# twice in production (Phase 12.8.5 checkpoint,
# `FATAL: could not open file "global/pg_filenode.map": Permission denied`),
# each time worked around live by a manual `chown -R 70:70` but never fixed
# here until now. Reading each image's real uid directly, every deploy,
# closes this permanently - it can't drift out of sync with a base image
# change the way a second hardcoded guess could.
postgres_uid="$(docker run --rm postgres:16-alpine id -u postgres)"
redis_uid="$(docker run --rm redis:7-alpine id -u redis)"
echo "    postgres:16-alpine runs as uid $postgres_uid, redis:7-alpine as uid $redis_uid"
chown -R "$postgres_uid:$postgres_uid" "$DATA_ROOT/postgres"
chown -R "$redis_uid:$redis_uid" "$DATA_ROOT/redis"
chown -R 10001:10001 "$DATA_ROOT/logs"

ROLLBACK_FILE="$DATA_ROOT/backups/last-deployed-commit.txt"
CURRENT_COMMIT="$(git rev-parse HEAD)"
echo "==> Recording current commit ($CURRENT_COMMIT) for rollback.sh"
if [ -f "$ROLLBACK_FILE" ]; then
  cp "$ROLLBACK_FILE" "$ROLLBACK_FILE.previous"
fi
echo "$CURRENT_COMMIT" > "$ROLLBACK_FILE"

echo "==> Building the application image"
"${COMPOSE[@]}" build app

echo "==> Starting/updating the stack"
"${COMPOSE[@]}" up -d

# 2026-08-29 deploy hardening: `docker compose up -d` only recreates a container when Compose's
# own change-detection thinks its resolved config differs from what's already running - a .env
# value changing (e.g. SMS_API_URL/SMS_API_KEY/SMS_SENDER) with everything else unchanged doesn't
# reliably trigger that, so `app` can keep running on a stale environment snapshot across deploys
# indefinitely. Confirmed root cause of a real production incident (2026-08-28): OTP requests
# failing because backend-app-1 was still running on an environment captured before its .env was
# fixed, two deploys later. Force it explicitly, every deploy, scoped to just `app` - the
# whole-stack `up -d` above still handles postgres/redis/nginx/certbot exactly as before, and
# `--force-recreate` only replaces the container, never the bind-mounted volumes/data underneath it.
echo "==> Force-recreating app to guarantee it never runs on a stale environment"
"${COMPOSE[@]}" up -d --force-recreate app

echo "==> Waiting for the app to report healthy (up to 2.5 min)"
container_id="$("${COMPOSE[@]}" ps -q app)"
for _ in $(seq 1 30); do
  status="$(docker inspect -f '{{.State.Health.Status}}' "$container_id" 2>/dev/null || echo unknown)"
  if [ "$status" = "healthy" ]; then
    echo "==> App is healthy."
    # A recreated container gets a new internal Docker network IP; nginx resolves its `proxy_pass`
    # upstream hostname once at its own startup and won't notice the change on its own - confirmed
    # live during the same incident (a 502 immediately after a manual force-recreate, until nginx
    # was restarted). Restarting it here closes that gap on every deploy, not just that one.
    echo "==> Restarting nginx so it re-resolves the app container's new address"
    "${COMPOSE[@]}" restart nginx
    "${COMPOSE[@]}" ps
    exit 0
  fi
  sleep 5
done

echo "App did not become healthy in time - check: docker compose -f docker-compose.prod.yml logs app" >&2
exit 1
