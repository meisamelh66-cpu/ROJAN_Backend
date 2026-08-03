#!/usr/bin/env bash
# One-time Let's Encrypt bootstrap for the ROJAN backend's Nginx reverse
# proxy — solves the chicken-and-egg problem where Nginx's HTTPS server
# block needs a certificate to exist before Nginx will even start, but
# Certbot needs Nginx running (to serve the HTTP-01 challenge) before it
# can issue that certificate.
#
# Nginx's own startup no longer depends on this script's ordering: the
# `cert-init` service in docker-compose.prod.yml guarantees *some*
# certificate (a throwaway self-signed one, if no real one exists yet) is
# on disk before Compose will even start Nginx, via `depends_on: cert-init:
# condition: service_completed_successfully`. That's enforced by Compose
# itself for every way Nginx could start (this script, scripts/deploy.sh,
# a restart, a reboot) — not just this one script's happy path. This
# script's job is now just to swap that throwaway certificate for a real
# one from Let's Encrypt.
#
# Run this manually, once, on the target VPS after DNS for the real
# domain already points at it (Let's Encrypt's HTTP-01 challenge will
# fail otherwise).
#
# Usage: DOMAIN=your.domain.com EMAIL=you@example.com ./init-letsencrypt.sh
set -euo pipefail

: "${DOMAIN:?Set DOMAIN, e.g. DOMAIN=api.rojan.example ./init-letsencrypt.sh}"
: "${EMAIL:?Set EMAIL for Lets Encrypt renewal/expiry notices}"
STAGING="${STAGING:-0}"   # 1 = use the Lets Encrypt staging CA (no rate limits, untrusted cert) - for rehearsing this script.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CONF_FILE="$REPO_ROOT/docker/nginx/conf.d/rojan.conf"
COMPOSE="docker compose -f $REPO_ROOT/docker-compose.prod.yml"

echo "==> Substituting CHANGE_ME_DOMAIN -> $DOMAIN in $CONF_FILE"
sed -i "s/CHANGE_ME_DOMAIN/$DOMAIN/g" "$CONF_FILE"

echo "==> Bringing up the full stack (cert-init guarantees Nginx has a certificate to boot with)"
$COMPOSE up -d

echo "==> Waiting for Nginx to report healthy (up to 2.5 min)"
container_id="$($COMPOSE ps -q nginx)"
status=unknown
for _ in $(seq 1 30); do
  status="$(docker inspect -f '{{.State.Health.Status}}' "$container_id" 2>/dev/null || echo unknown)"
  if [ "$status" = "healthy" ]; then
    break
  fi
  sleep 5
done
if [ "$status" != "healthy" ]; then
  echo "Nginx did not become healthy - check: docker compose -f docker-compose.prod.yml logs nginx" >&2
  exit 1
fi

echo "==> Removing the throwaway certificate so Certbot issues a clean lineage"
$COMPOSE run --rm --entrypoint rm certbot -rf \
  "/etc/letsencrypt/live/$DOMAIN" \
  "/etc/letsencrypt/archive/$DOMAIN" \
  "/etc/letsencrypt/renewal/$DOMAIN.conf"

echo "==> Requesting the real certificate from Let's Encrypt"
STAGING_ARG=()
if [ "$STAGING" = "1" ]; then
  STAGING_ARG=(--staging)
fi
# certbot/certbot's image entrypoint is already the `certbot` binary, so
# no --entrypoint override is needed for this call.
$COMPOSE run --rm certbot certonly --webroot -w /var/www/certbot \
  "${STAGING_ARG[@]}" \
  --email "$EMAIL" --agree-tos --no-eff-email \
  -d "$DOMAIN"

echo "==> Reloading Nginx with the real certificate"
$COMPOSE exec nginx nginx -s reload

echo "==> Done. Certbot's own renewal loop (the 'certbot' service in docker-compose.prod.yml) takes over from here."
