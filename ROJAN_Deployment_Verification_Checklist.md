# ROJAN Deployment Verification Checklist

Reflects the current, existing deployment pipeline (documented in depth in `DEPLOYMENT.md`) — this is a condensed, action-oriented checklist for this readiness pass, not a redesign.

## 1. Build the jar

```bash
./gradlew clean build
./gradlew bootJar
```

**Verified this session:** both commands ran to `BUILD SUCCESSFUL`. Output:
```
bootstrap/build/libs/bootstrap-0.1.0-SNAPSHOT.jar
```
Confirmed present, ~94.3 MB (size stable across recent runs — not truncated/corrupted).

- [x] `clean build` passes (full test suite, all modules)
- [x] `bootJar` produces the executable jar
- [ ] Jar copied to the production server *(not done from this environment — no SSH credentials available; must be done manually, see `DEPLOYMENT.md` §"Jar deployment flow")*

## 2. Docker image

`Dockerfile` **copies a pre-built jar** — it does not run Gradle inside the image:
```dockerfile
COPY bootstrap/build/libs/bootstrap-0.1.0-SNAPSHOT.jar app.jar
```
- [ ] Confirm the jar at that path on the **build host/server** is the one just built (this has been the source of at least one past deployment incident in this project — a stale jar silently getting baked into the image)
- [ ] `docker compose build app`
- [ ] `docker compose up -d --no-deps app`

## 3. Environment variables

Required (app refuses to start without these):
- [ ] `JWT_SECRET` — `openssl rand -base64 48`
- [ ] `DB_PASSWORD`

Required for a working stack but with usable defaults for non-prod:
- [ ] `DB_NAME` / `DB_USERNAME` (default `rojan`/`rojan` in dev compose)
- [ ] `DOMAIN_NAME` / `LETSENCRYPT_EMAIL` — prod only, for the Nginx/Certbot SSL bootstrap

Optional (safe defaults):
- `JWT_ISSUER`, `JWT_ACCESS_TTL_MINUTES`, `JWT_REFRESH_TTL_DAYS`, `DB_POOL_SIZE`, `REDIS_PASSWORD`, `ROJAN_DATA_ROOT`, `SPRINGDOC_ENABLED`

Full authoritative list: `.env.example`.

## 4. Database migration

- Flyway, forward-only, `spring.flyway.enabled: true`, `locations: classpath:db/migration`.
- `spring.jpa.hibernate.ddl-auto: validate` — **Hibernate never mutates schema**; `db/migration/V1..V4__*.sql` is the single source of truth.
- [ ] After deploy, confirm migrations ran: `docker compose -f docker-compose.prod.yml logs app | grep -i flyway`
- [ ] No down-migrations exist — a schema-incompatible rollback requires restoring from `scripts/backup.sh`'s `pg_dump` output, not an automatic revert (`DEPLOYMENT.md` §8, unchanged by this task).

## 5. Health check

- App: `HEALTHCHECK` in both `Dockerfile` (image-level) and `docker-compose.prod.yml` (compose-level) hit `curl -f http://localhost:8080/actuator/health`.
- [ ] `docker compose -f docker-compose.prod.yml ps app` → expect `healthy`
- [ ] `curl https://<domain>/actuator/health` → expect `{"status":"UP"}`
- `app`'s `depends_on`: `postgres: condition: service_healthy`, `redis: condition: service_healthy` (both compose files) — app won't even attempt to start serving until both are healthy.

## 6. Post-deploy smoke test (this task's scope)

- [ ] `POST /api/v1/auth/register` + `/login` → confirm `200`/`201` and a token pair
- [ ] `GET /api/v1/dashboard/insights` with no token → confirm `401` with body `{"errorCode":"AUTH_UNAUTHORIZED","message":"Authentication required"}` **(new contract verified this session — confirm it survived the actual deploy, not just the local build)**
- [ ] `GET /api/v1/dashboard/insights` with a valid token for a user with no salon → confirm `404`, `errorCode: SALON_NOT_FOUND`
- [ ] `GET /api/v1/dashboard/insights` with a valid token for a salon owner → confirm `200` with the full `revenue`/`bookings`/`customers`/`services`/`recommendations` shape

## Status as of this task

| Item | Status |
|---|---|
| `clean build` | ✅ Passed |
| `bootJar` | ✅ Passed, jar verified present |
| Jar transferred to server | ❌ Not done (no credentials in this environment) |
| Docker image rebuilt on server | ❌ Not done |
| Live health check | ❌ Not verified (nothing deployed this session) |
| Live smoke test | ❌ Not verified (nothing deployed this session) |

**This task builds and locally verifies the artifact only. Someone with server access must complete the unchecked items above before this is truly production-live.**
