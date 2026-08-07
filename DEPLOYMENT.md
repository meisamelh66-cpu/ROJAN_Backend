# Production Deployment — Ubuntu VPS

Status: **infrastructure prepared, not yet deployed.** Nothing in this
document has been run against the real server. No new features or
business logic were introduced to prepare it — every file here is
ops/infra configuration layered on top of the existing, frozen
application behavior.

## 1. Audit — current deployment requirements

From `README.md`, `build.gradle.kts` files, and
`bootstrap/src/main/resources/application.yml`:

| Requirement | Detail |
|---|---|
| Runtime | Java 21 (`eclipse-temurin:21-jre` in production) |
| Framework | Spring Boot 3.3.5, Kotlin 2.0.21 |
| Database | PostgreSQL 16, schema owned by Flyway (`db/migration`), `ddl-auto: validate` — Hibernate never mutates schema |
| Cache | Redis 7 (wired, unconsumed so far — README: "prepared, no feature consumes it yet") |
| Auth | Stateless JWT (HS256) — `JWT_SECRET` has no default; the app refuses to start without one |
| Config | 100% environment-variable driven, no hardcoded secrets |
| Health | Spring Boot Actuator already present, `/actuator/health` and `/actuator/health/**` already `permitAll()` in `SecurityConfig.kt` — no security code change was needed to make health checks work |
| Target VPS | Ubuntu 24.04 LTS, Java 21, Docker + Docker Compose plugin already installed; `/opt/rojan/{backend,frontend,nginx,postgres,redis,uploads,logs,backups}` already provisioned |

**Kafka — audited and deliberately excluded from this deployment.**
`infrastructure/build.gradle.kts` depends on `spring-kafka` and
`KafkaProducerConfig` wires a real, connectable `KafkaTemplate` — but
nothing in the app calls it yet (README: "no topic in use yet"), no
`kafka` directory exists in the provisioned `/opt/rojan` layout, and it
isn't in this milestone's service list. Verified this is safe *only*
because of one specific finding: Spring Boot auto-configures a Kafka
health indicator the instant `spring-kafka` is on the classpath, and it
would have pinged the (here, nonexistent) broker and dragged the whole
`/actuator/health` response to `DOWN` — which would have made the app's
own container healthcheck (and Nginx's dependency on it) permanently
fail even though Postgres/Redis/the API itself are fine.
`application-prod.yml` disables that one indicator
(`management.health.kafka.enabled: false`) specifically because of this
finding. Re-enable it, and add a `kafka` service back to
`docker-compose.prod.yml`, the day a real feature needs Kafka.

**`/opt/rojan/uploads` and `/opt/rojan/frontend` — provisioned, not used
by this deployment.** No endpoint in `API_CONTRACT.md` accepts file
uploads, so nothing is mounted into `uploads/`. `frontend/` belongs to a
separate deployment (this repository is backend-only) and this milestone
does not touch it.

**Gaps found and closed by this milestone:**
- No production compose file → `docker-compose.prod.yml`.
- No reverse proxy / TLS termination → `docker/nginx/`.
- No `.env` template → `.env.example`.
- No `prod` Spring profile (only `test` had this pattern via `application-test.yml`) → `bootstrap/src/main/resources/application-prod.yml`.
- No deployment lifecycle scripts → `scripts/deploy.sh`, `scripts/rollback.sh`, `scripts/backup.sh`.
- Runtime image had no HTTP client for a real healthcheck → `curl` added to the `Dockerfile` runtime stage, plus an image-level `HEALTHCHECK` instruction (the only `Dockerfile` changes).
- No production logging config → `application-prod.yml` writes a rolling file log to `/app/logs` (bind-mounted to `/opt/rojan/logs`) in addition to console output.

**Gaps found but explicitly out of scope for this milestone** (infra prep
only, no business logic changes):
- No CI pipeline / container registry — this deployment builds the image
  on the VPS itself from source (`docker compose build`). Fine for a
  single-VPS deployment; revisit if multi-server/zero-downtime deploys
  are ever needed.
- No refresh-token revocation/rotation storage (tracked in
  `API_CONTRACT.md`'s "Known gaps" already — unrelated to deployment).
- `rollback.sh` reverts application code only, not the database schema —
  see §8 below for why that's a deliberate boundary, not an oversight.

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
   +--> postgres (internal only, bind-mounted to /opt/rojan/postgres)
   +--> redis    (internal only, bind-mounted to /opt/rojan/redis, AOF)

 certbot: renews the Let's Encrypt cert on a loop, shares
          /opt/rojan/nginx/{webroot,letsencrypt} with Nginx
```

Postgres and Redis are reachable only from other containers on the
compose-created `rojan_net` bridge network — ports are never published to
the host.

## 3. Files this milestone added/changed

| File | Purpose |
|---|---|
| `docker-compose.prod.yml` | Production service topology: restart policies, `/opt/rojan`-bind-mounted persistence, internal-only Postgres/Redis, app healthcheck, Nginx + Certbot |
| `docker/nginx/nginx.conf` | Nginx process-wide defaults (gzip, logging, body-size limit) |
| `docker/nginx/conf.d/rojan.conf` | HTTP→HTTPS redirect, ACME challenge location, HTTPS reverse proxy to the app, security headers |
| `docker/nginx/init-letsencrypt.sh` | One-time bootstrap script that swaps the throwaway certificate (see `cert-init` below) for a real Let's Encrypt one (see §7) |
| `.env.example` | Every environment variable production needs, documented, no real values |
| `bootstrap/src/main/resources/application-prod.yml` | `prod` Spring profile: graceful shutdown, tightened actuator detail exposure, Kafka health indicator off, Swagger UI off by default, rolling file logging |
| `Dockerfile` (modified) | Added `curl` + an image-level `HEALTHCHECK`; creates `/app/logs` owned by the runtime user |
| `scripts/deploy.sh` | Provisions host directory ownership, builds, brings the stack up, waits for health |
| `scripts/rollback.sh` | Reverts application code to the previous deploy's commit and restarts just the `app` service |
| `scripts/backup.sh` | `pg_dump`s the database to `/opt/rojan/backups`, prunes anything older than 14 days |

## 4. Environment variables

See `.env.example` for the authoritative, commented list. Summary:

| Variable | Required | Purpose |
|---|---|---|
| `JWT_SECRET` | Yes | HMAC signing key, ≥ 32 chars (`openssl rand -base64 48`) |
| `JWT_ISSUER` / `JWT_ACCESS_TTL_MINUTES` / `JWT_REFRESH_TTL_DAYS` | No | Token issuer/lifetimes, sensible defaults |
| `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD` | Yes (password) | PostgreSQL credentials |
| `DB_POOL_SIZE` | No | Hikari pool size, defaults to 10 |
| `REDIS_PASSWORD` | No | Optional — Redis isn't host-exposed either way |
| `SMS_PROVIDER` | No | Blank (default) keeps `RealSmsProviderAdapter` (the "Simple" endpoint) active. Set to `melipayamak-shared` to switch to `MeliPayamakSharedPatternProvider` (MeliPayamak's templated "Shared Pattern" endpoint) instead |
| `MELIPAYAMAK_API_KEY` / `MELIPAYAMAK_BODY_ID` | Only if `SMS_PROVIDER=melipayamak-shared` | API key (URL path segment against `console.melipayamak.com/api/send/shared`) and the pre-approved template id — ROJAN's own generated OTP is relayed into that template as `args[0]`, never generated by MeliPayamak itself |
| `ROJAN_DATA_ROOT` | No | Defaults to `/opt/rojan`, the already-provisioned path |
| `DOMAIN_NAME` / `LETSENCRYPT_EMAIL` | Yes, for SSL bootstrap | `DOMAIN_NAME` is read by `docker-compose.prod.yml`'s `cert-init` service directly (and by `docker/nginx/init-letsencrypt.sh`, which additionally uses both vars for the real Certbot request) |

## 5. PostgreSQL & Redis persistence

- **PostgreSQL**: bind-mounted at `${ROJAN_DATA_ROOT}/postgres` →
  `/var/lib/postgresql/data`.
- **Redis**: bind-mounted at `${ROJAN_DATA_ROOT}/redis` → `/data`, with
  `--appendonly yes` so the moment a real caching/session feature lands,
  it isn't quietly non-durable by accident. `REDIS_PASSWORD` is honored
  if set, optional otherwise.
- Both official images run internally as uid/gid `999`; bind mounts take
  on the *host* directory's ownership rather than the image's own
  chown-on-first-boot behavior (that only applies to Docker-managed named
  volumes). `scripts/deploy.sh` runs `chown -R 999:999` on both
  directories before first bring-up — verify with `docker run --rm
  <image> id` if either image's base UID ever changes upstream.

## 6. Logging (task 7)

`application-prod.yml` adds `logging.file.name: /app/logs/rojan-backend.log`
(bind-mounted to `${ROJAN_DATA_ROOT}/logs`) alongside Spring Boot's normal
console output, so `docker compose logs app` keeps working *and* history
survives container recreation. Rolling policy: 50MB per file, 14 days
retention, 1GB total cap (`logging.logback.rollingpolicy.*`). Nginx access
and error logs are bind-mounted to `${ROJAN_DATA_ROOT}/nginx/logs`.

## 7. SSL / Let's Encrypt plan

Nginx's HTTPS server block requires certificate files to exist before
Nginx will even start — but Let's Encrypt's HTTP-01 challenge requires
Nginx (or something) already serving plain HTTP on the target domain.

This ordering is enforced at the **Compose level**, not by script
sequencing: `docker-compose.prod.yml`'s `cert-init` service runs before
Nginx on every `docker compose up` and generates a throwaway 1-day
self-signed certificate at the exact path Let's Encrypt would use *if*
no real certificate is present yet (a no-op once a real one exists).
Nginx's `depends_on: cert-init: condition: service_completed_successfully`
means Compose will not even attempt to start Nginx until that placeholder
(or a real cert, on later runs) is on disk — so Nginx starting no longer
depends on `init-letsencrypt.sh`, `scripts/deploy.sh`, a restart, or a
reboot happening in any particular order.

`docker/nginx/init-letsencrypt.sh` then swaps the throwaway certificate
for a real one:

1. Substitute the real domain into `docker/nginx/conf.d/rojan.conf`
   (replaces the `CHANGE_ME_DOMAIN` placeholder).
2. `docker compose up -d` — `cert-init` provisions the throwaway
   certificate, then Nginx starts and serves HTTP (including the ACME
   challenge path) and HTTPS (with the untrusted placeholder cert).
3. Wait for Nginx's healthcheck to go healthy.
4. Delete the placeholder certificate (Certbot needs a clean lineage,
   not a plain file it doesn't manage, at `live/$DOMAIN`).
5. Run `certbot certonly --webroot` against the now-running Nginx to
   obtain the real certificate for the domain.
6. `nginx -s reload` to pick up the real certificate — zero downtime, no
   container restart.
7. From here on, the long-running `certbot` service in
   `docker-compose.prod.yml` renews automatically (checks every 12h;
   Let's Encrypt certs are valid 90 days) — reloading Nginx after a
   successful renewal is the one remaining manual/cron step (see the
   checklist below).

Prerequisites: DNS for the real domain must already point at the VPS's
public IP, and ports 80/443 must be reachable from the internet.
Rehearse safely first with `STAGING=1` (Let's Encrypt's staging CA has no
rate limits but issues untrusted certs).

## 8. Rollback boundary — why it's app-code-only

`scripts/rollback.sh` checks out the previous commit and rebuilds/restarts
only the `app` service. It deliberately does **not** attempt to revert
the database: Flyway migrations are forward-only by design (`ddl-auto:
validate` — Hibernate never mutates schema, `db/migration` is the single
source of truth), and there is no generic, safe way to auto-generate a
down-migration. If the deploy being rolled back introduced a schema
change the previous commit's code can't work with, restore Postgres from
the matching `backup.sh` archive (§10 below) *in addition to* running
`rollback.sh` — don't rely on the script alone in that case.

## 9. Container dependency verification (task 9)

| Service | Depends on | Verified via |
|---|---|---|
| `app` | Postgres reachable + migrated, Redis reachable | `depends_on: condition: service_healthy` on both; app itself won't complete startup without a working datasource (JPA/Flyway) |
| `app` | Kafka | **Not required** — see §1's audit finding; health indicator explicitly disabled so this is a verified non-dependency for this deployment, not an oversight |
| `nginx` | `app` healthy, `cert-init` completed | `depends_on: condition: service_healthy` (app) and `condition: service_completed_successfully` (cert-init) — the latter guarantees a certificate exists before Nginx's config is even parsed, so Nginx can never crash-loop on a missing cert regardless of start order; Nginx's own healthcheck hits a static `/.well-known/healthcheck` location independent of the app, so it reports healthy even during a legitimate app restart |
| `cert-init` | none | Runs to completion before Nginx starts; idempotent — detects an existing real certificate and exits immediately without touching it |
| `certbot` | Nginx serving the ACME webroot path | Ordering handled by `init-letsencrypt.sh`, not by compose `depends_on` (the first-run bootstrap is inherently sequential/manual — see §7) |

## 10. Deployment checklist

Nothing below has been executed. This is the procedure for whoever runs
the actual deployment.

### One-time VPS setup (already done per the task brief, listed for completeness)
- [x] Ubuntu 24.04 LTS, Java 21, Docker, Docker Compose plugin
- [x] `/opt/rojan/{backend,frontend,nginx,postgres,redis,uploads,logs,backups}` created
- [ ] Firewall (ufw or cloud provider security group): allow only 22 (SSH), 80, 443
- [ ] Point the real domain's DNS A/AAAA record at the VPS's public IP; confirm it resolves
- [ ] `git clone` this repository into `/opt/rojan/backend`

### Configuration
- [ ] `cp .env.example .env` inside `/opt/rojan/backend`
- [ ] Generate and set `JWT_SECRET`: `openssl rand -base64 48`
- [ ] Set `DB_PASSWORD` to a strong random value
- [ ] Set `DOMAIN_NAME` and `LETSENCRYPT_EMAIL`
- [ ] Confirm `.env` is not tracked by git (`git status` should not show it)

### Startup (first deploy)
- [ ] `DOMAIN=<real domain> EMAIL=<real email> ./docker/nginx/init-letsencrypt.sh` (rehearse with `STAGING=1` first)
- [ ] `sudo ./scripts/deploy.sh` — builds the image, brings up the full stack, waits for the app to report healthy
- [ ] `docker compose -f docker-compose.prod.yml ps` — confirm every service is `healthy`/`running`
- [ ] `curl https://<real domain>/actuator/health` — expect `{"status":"UP"}`
- [ ] Confirm Flyway ran the expected migrations: `docker compose -f docker-compose.prod.yml logs app | grep -i flyway`
- [ ] Smoke-test one real endpoint (e.g. `POST /api/v1/auth/register` per `API_CONTRACT.md`)

### Shutdown
- [ ] `docker compose -f docker-compose.prod.yml stop` — graceful (server.shutdown: graceful in `application-prod.yml` lets in-flight requests finish, bounded to 20s)
- [ ] `docker compose -f docker-compose.prod.yml down` — stop and remove containers (data survives, it's all bind-mounted to `/opt/rojan`)

### Backup
- [ ] `./scripts/backup.sh` — manual, or:
- [ ] Add it to cron for unattended daily backups (example command is inside the script's header comment)
- [ ] Periodically copy `/opt/rojan/backups` off-VPS — this milestone prepares local persistence and backup tooling, not off-site/disaster-recovery storage; that's a real follow-up gap, not silently solved here

### Restore
- [ ] Stop the app so nothing writes during restore: `docker compose -f docker-compose.prod.yml stop app`
- [ ] `gunzip -c /opt/rojan/backups/rojan-postgres-<timestamp>.sql.gz | docker compose -f docker-compose.prod.yml exec -T postgres psql -U <DB_USERNAME> <DB_NAME>`
- [ ] `docker compose -f docker-compose.prod.yml start app`
- [ ] Verify via `/actuator/health` and a real read endpoint

### Upgrade
- [ ] `./scripts/backup.sh` first, always
- [ ] `git pull` (or `git checkout <tag>`) inside `/opt/rojan/backend`
- [ ] `sudo ./scripts/deploy.sh` — rebuilds and restarts with the new code; Flyway applies any new migrations automatically on boot
- [ ] Verify health + smoke-test as in "Startup" above

### Rollback
- [ ] `sudo ./scripts/rollback.sh` — reverts application code to the previous deploy's commit
- [ ] If the upgrade being rolled back included a schema migration, also restore the pre-upgrade backup (see "Restore" above) — `rollback.sh` intentionally does not do this for you (§8)

### Ongoing
- [ ] Confirm the `certbot` service is running (`docker compose -f docker-compose.prod.yml ps certbot`) — it renews automatically, but Nginx needs an explicit `nginx -s reload` after a renewal to pick up the new cert; script that as a cron entry (`docker compose -f docker-compose.prod.yml exec nginx nginx -s reload`) or plan to run it manually every ~60 days
- [ ] Set up log shipping / monitoring if this VPS doesn't already have it — `/opt/rojan/logs/rojan-backend.log` and `/opt/rojan/nginx/logs/*.log` are the two sources

### Explicitly not done by this milestone
- No actual deployment was run.
- No CI/CD or container registry — the image is built on the VPS from source.
- No load testing / capacity planning (Hikari pool size, JVM heap, Nginx worker tuning all use conservative defaults, not measured ones).
- No off-site backup storage.
