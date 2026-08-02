#!/usr/bin/env bash
# One-time Let's Encrypt bootstrap for the ROJAN backend's Nginx reverse
# proxy — solves the chicken-and-egg problem where Nginx's HTTPS server
# block needs a certificate to exist before Nginx will even start, but
# Certbot needs Nginx running (to serve the HTTP-01 challenge) before it
# can issue that certificate.
#
# Adapted from the well-known certbot/nginx docker-compose bootstrap
# pattern (docker-compose-letsencrypt-nginx-proxy-companion and certbot's
# own docs use the same shape): stand up Nginx with a temporary
# self-signed placeholder certificate first, then swap it for the real
# one once Certbot succeeds.
#
# NOT executed as part of this milestone — see DEPLOYMENT.md. Run this
# manually, once, on the target VPS after DNS for the real domain already
# points at it (Let's Encrypt's HTTP-01 challenge will fail otherwise).
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

echo "==> Creating a temporary self-signed certificate so Nginx can start"
$COMPOSE run --rm --entrypoint sh certbot -c "
  mkdir -p /etc/letsencrypt/live/$DOMAIN &&
  openssl req -x509 -nodes -newkey rsa:2048 -days 1 \
    -keyout /etc/letsencrypt/live/$DOMAIN/privkey.pem \
    -out /etc/letsencrypt/live/$DOMAIN/fullchain.pem \
    -subj '/CN=localhost'
"

echo "==> Starting Nginx (and its dependencies) with the placeholder cert"
$COMPOSE up -d nginx

echo "==> Deleting the placeholder certificate"
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
