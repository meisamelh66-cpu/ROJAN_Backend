# Production Deployment — Ubuntu VPS

Status: **infrastructure prepared, not yet deployed.** Nothing in this
document has been run against a real server. No new features or business
logic were introduced to prepare it — every file here is ops/infra
configuration layered on top of the existing, frozen application behavior.

## 1. Audit — current deployment requirements

From `README.md`, `build.gradle.kts` files, and `bootstrap/src/main/resources/application.yml`:

| Requirement | Detail |
|---|---|
| Runtime | Java 21 (`eclipse-temurin:21-jre` in production; the existing `Dockerfile` already multi-stage builds with `eclipse-temurin:21-jdk`) |
| Framework | Spring Boot 3.3.5, Kotlin 2.0.21 |
| Database | PostgreSQL 16, schema owned by Flyway (`db/migration`), `ddl-auto: validate` — Hibernate never mutates schema |
| Cache/broker | Redis 7 (wired, unconsumed), Kafka 3.8 KRaft single-node (wired, unconsumed) — both real dependencies the app fails to start without ([`infrastructure/build.gradle.kts`](infrastructure/build.gradle.kts)) |
| Auth | Stateless JWT (HS256) — `JWT_SECRET` has no default; the app refuses to start without one |
| Config | 100% environment-variable driven, no hardcoded secrets |
| Health | Spring Boot Actuator already present, `/actuator/health` and `/actuator/health/**` already `permitAll()` in `SecurityConfig.kt` — no code change was needed to make health checks work |
| Existing container image | `Dockerfile` already builds a slim runtime image running as a non-root user (`rojan`, uid 10001) |
| Existing compose | Root `docker-compose.yml` is dev-only: publishes Postgres/Redis/Kafka ports to the host, plaintext default credentials, no restart policies, no app healthcheck — correct for local dev, not for a VPS |

**Gaps found and closed by this milestone:**
- No production compose file (host ports were open on every service; no persistent volumes for Redis/Kafka; no app-level healthcheck) → `docker-compose.prod.yml`.
- No reverse proxy / TLS termination at all → `docker/nginx/`.
- No `.env` template — dev compose had credentials inlined → `.env.example`.
- No `prod` Spring profile (test already had this pattern via `application-test.yml`; prod didn't) → `bootstrap/src/main/resources/application-prod.yml`.
- Runtime image had no HTTP client for a real healthcheck → `curl` added to the `Dockerfile` runtime stage (the only Dockerfile change).

**Gaps found but explicitly out of scope for this milestone** (infra prep only, no business logic changes):
- No CI pipeline / container registry — this deployment prep builds the image on the VPS itself from source (`docker compose build`), same as the existing dev compose does. Fine for a single-VPS deployment; revisit if multi-server/zero-downtime deploys are ever needed.
- No refresh-token revocation/rotation storage (tracked in `API_CONTRACT.md`'s "Known gaps" already — unrelated to deployment).
- `management.endpoint.health.show-details` defaults to `when-authorized` in dev; tightened to `never` in `application-prod.yml` (see that file's comment) — the one config value in this milestone that's arguably "security posture" rather than pure ops, called out here for visibility.

## 2. Architecture

```
Internet
   |
   v
 Nginx (80/443, only host-exposed ports besides SSH)
   |  terminates TLS, proxies to app:8080
   v
 app (Spring Boot, internal network only)
   |
   +--> postgres (internal only, persistent volume)
   +--> redis    (internal only, persistent volume, AOF)
   +--> kafka    (internal only, persistent volume)

 certbot: renews the Let's Encrypt cert on a loop, shares a volume with Nginx
```

All of Postgres/Redis/Kafka are reachable only from other containers on
the compose-created `rojan_net` bridge network — `docker-compose.prod.yml`
does not publish their ports to the host, unlike the dev compose file.

## 3. Files this milestone added

| File | Purpose |
|---|---|
| `docker-compose.prod.yml` | Production service topology: restart policies, persistent volumes, internal-only DB/cache/broker, app healthcheck, Nginx + Certbot |
| `docker/nginx/nginx.conf` | Nginx process-wide defaults (gzip, logging, body-size limit) |
| `docker/nginx/conf.d/rojan.conf` | HTTP→HTTPS redirect, ACME challenge location, HTTPS reverse proxy to the app, security headers |
| `docker/nginx/init-letsencrypt.sh` | One-time bootstrap script that solves the "Nginx needs a cert to start, Certbot needs Nginx running to issue one" ordering problem (see §5) |
| `.env.example` | Every environment variable production needs, documented, no real values |
| `bootstrap/src/main/resources/application-prod.yml` | `prod` Spring profile: graceful shutdown, tightened actuator detail exposure, Swagger UI off by default, quieter logging |
| `Dockerfile` (modified) | Added `curl` to the runtime image so `docker-compose.prod.yml`'s app healthcheck can actually call `/actuator/health` |

## 4. PostgreSQL & Redis persistence (tasks 6-7)

- **PostgreSQL**: unchanged strategy from dev compose — named volume
  `rojan_postgres_data` mounted at `/var/lib/postgresql/data`. Already
  correct in the existing dev file; carried over as-is.
- **Redis**: dev compose had no volume at all (any cached data vanishes on
  container recreate — acceptable for a stack with no Redis consumer yet).
  Production compose adds `rojan_redis_data` + `--appendonly yes` so the
  moment a real caching/session feature lands, it isn't quietly
  non-durable by accident. `REDIS_PASSWORD` is honored if set, optional
  otherwise (Redis isn't host-exposed either way).
- **Kafka**: dev compose had no volume either — KRaft's own metadata log
  would be wiped on every container recreate (a "new cluster" each time).
  Production compose adds `rojan_kafka_data`.

## 5. SSL / Let's Encrypt plan (task 9)

Nginx's HTTPS server block requires certificate files to exist before
Nginx will even start — but Let's Encrypt's HTTP-01 challenge requires
Nginx (or something) already serving plain HTTP on the target domain.
`docker/nginx/init-letsencrypt.sh` resolves that ordering with the
standard community pattern:

1. Substitute the real domain into `docker/nginx/conf.d/rojan.conf`
   (replaces the `CHANGE_ME_DOMAIN` placeholder).
2. Generate a throwaway 1-day self-signed certificate at the exact path
   Let's Encrypt would use, purely so Nginx has *something* to load.
3. Start Nginx — it now serves HTTP (including the ACME challenge path)
   and HTTPS (with the untrusted placeholder cert).
4. Delete the placeholder certificate.
5. Run `certbot certonly --webroot` against the now-running Nginx to
   obtain the real certificate for the domain.
6. `nginx -s reload` to pick up the real certificate — zero downtime,
   no container restart.
7. From here on, the long-running `certbot` service in
   `docker-compose.prod.yml` renews automatically (checks every 12h;
   Let's Encrypt certs are valid 90 days, so this renews well before
   expiry) and reloading Nginx after a successful renewal is the one
   remaining manual/cron step — see the checklist below.

Prerequisites before running the script: DNS for the real domain must
already point at the VPS's public IP (Let's Encrypt's HTTP-01 challenge
validates by connecting to the domain over the public internet), and
ports 80/443 must be reachable from the internet.

Rehearse safely first with `STAGING=1` (Let's Encrypt's staging CA has no
rate limits but issues untrusted certs) before running for real.

## 6. Deployment checklist

Nothing below has been executed. This is the procedure for whoever runs
the actual deployment.

### One-time VPS setup
- [ ] Provision Ubuntu VPS, apply OS updates (`apt update && apt upgrade`)
- [ ] Create a non-root deploy user with sudo, disable root SSH login
- [ ] Install Docker Engine + the Docker Compose plugin (`docker compose version` should work)
- [ ] Firewall (ufw or cloud provider security group): allow only 22 (SSH), 80, 443 — Postgres/Redis/Kafka ports must NOT be open to the internet (they aren't published by `docker-compose.prod.yml` at all, but double-check if this VPS is reused for anything else)
- [ ] Point the real domain's DNS A/AAAA record at the VPS's public IP; confirm it resolves before continuing
- [ ] `git clone` this repository onto the VPS

### Configuration
- [ ] `cp .env.example .env`
- [ ] Generate and set `JWT_SECRET`: `openssl rand -base64 48`
- [ ] Set `DB_PASSWORD` to a strong random value
- [ ] Set `DOMAIN_NAME` and `LETSENCRYPT_EMAIL` in `.env`
- [ ] Review every other value in `.env` against `.env.example`'s comments; confirm `.env` is not tracked by git (`git status` should not show it — it's in `.gitignore`)

### First bring-up
- [ ] `DOMAIN=<real domain> EMAIL=<real email> ./docker/nginx/init-letsencrypt.sh` (rehearse with `STAGING=1` first)
- [ ] `docker compose -f docker-compose.prod.yml up -d --build`
- [ ] `docker compose -f docker-compose.prod.yml ps` — confirm every service is `healthy`/`running`
- [ ] `curl https://<real domain>/actuator/health` — expect `{"status":"UP"}`
- [ ] Confirm Flyway ran the expected migrations: `docker compose -f docker-compose.prod.yml logs app | grep -i flyway`
- [ ] Smoke-test one real endpoint (e.g. `POST /api/v1/auth/register` per `API_CONTRACT.md`)

### Ongoing
- [ ] Confirm the `certbot` service is running (`docker compose -f docker-compose.prod.yml ps certbot`) — it renews automatically, but Nginx needs an explicit `nginx -s reload` after a renewal to pick up the new cert; either script that as a `docker compose exec nginx nginx -s reload` cron entry, or plan to run it manually every ~60 days
- [ ] Set up off-VPS backups for the `rojan_postgres_data` volume (this milestone prepares persistence, not backup/DR — worth a follow-up)
- [ ] Set up log shipping / monitoring if this VPS doesn't already have it (Nginx `access.log`/`error.log` and the app's stdout are the two sources)

### Explicitly not done by this milestone
- No actual deployment was run.
- No CI/CD or container registry — the image is built on the VPS from source.
- No load testing / capacity planning (Hikari pool size, JVM heap, Nginx worker tuning all use conservative defaults, not measured ones).
