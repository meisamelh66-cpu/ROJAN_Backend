# ROJAN Backend

Production backend for ROJAN AI. Standalone repository — independent from
[`ROJAN_DesignLab`](../ROJAN_DesignLab) (the Android app); communicates with
it (and future clients) purely over REST/JSON.

## Stack

- Kotlin, Java 21
- Spring Boot 3, Spring Security (JWT, stateless)
- Gradle Kotlin DSL, multi-module
- PostgreSQL + Flyway
- Redis (prepared — client/config wired, no feature consumes it yet)
- Kafka (prepared — producer wired, no topic in use yet)
- Docker / Docker Compose
- springdoc-openapi (Swagger UI)

## Architecture

Clean Architecture, five Gradle modules, dependencies point inward:

```
domain          <- no framework dependencies at all (entities, ports, exceptions)
application     <- depends on domain only (use cases, output ports)
infrastructure  <- depends on domain + application (JPA, JWT, Redis, Kafka adapters)
api             <- depends on domain + application (controllers, DTOs, OpenAPI)
bootstrap       <- depends on all four (Spring Boot entry point, application.yml)
```

`infrastructure` and `api` never depend on each other directly — Spring's
component scan (rooted at `ai.rojan.backend` in `BackendApplication`) wires
them together at runtime through the ports declared in `domain`/`application`.
This is what lets `infrastructure` implement `UserRepository`/
`PasswordEncoderPort`/`TokenProviderPort` while `api` only ever depends on
the interfaces.

## Implemented so far (auth vertical slice — frozen baseline)

- `User` domain entity + `UserRole` (`CUSTOMER`, `MANAGER`, `SPECIALIST`)
- Auth use cases: register, login, refresh — all pure Kotlin, unit-tested
  against in-memory fakes (no Spring context required)
- JWT access/refresh tokens (`io.jsonwebtoken`, HS256), each carrying its
  own type — a refresh token is rejected by protected endpoints and an
  access token is rejected by `/auth/refresh`; stateless Spring Security
  filter chain
- PostgreSQL persistence via Spring Data JPA + a repository-pattern adapter;
  Flyway migration `V1__init_schema.sql` owns the schema (`ddl-auto: validate`,
  Hibernate never mutates the schema itself)
- `POST /api/v1/auth/register`, `/login`, `/refresh`, `GET /api/v1/users/me`
  — full contract in [`API_CONTRACT.md`](API_CONTRACT.md)
- OpenAPI/Swagger UI at `/swagger-ui/index.html`
- Integration tests (`bootstrap` module) exercise the real HTTP layer end to
  end against a real, embedded (no-Docker) PostgreSQL via
  `io.zonky.test:embedded-database-spring-test`
- Redis + Kafka: connection/config beans only, ready for the first real
  caching/eventing use case to consume

This is a frozen baseline as of commit `<see git log>` — extend additively
(new endpoints/use cases consuming the same primitives) rather than
reworking the auth mechanism itself; get explicit sign-off first for
anything architectural, same convention as `ROJAN_DesignLab`'s frozen
baselines.

## Also built since the frozen baseline above

Salon management (salon/branch/service-category/service/specialist CRUD),
a booking engine (working hours, specialist schedules, computed available
slots, and a concurrency-safe booking lifecycle), and an API-hardening pass
(pagination/filtering/sorting, standardized errors with a `traceId`,
`Idempotency-Key` support on booking creation, and an OWASP-driven fix
redacting specialist leave/override/block `reason` fields from non-owner
viewers). Full contract in [`API_CONTRACT.md`](API_CONTRACT.md).

## Not yet built

Payments, CRM, notifications, reviews, staff/specialist login accounts
beyond the role enum + optional link, Kafka topics/consumers, Redis-backed
caching, CI pipeline, refresh-token revocation/rotation storage (see "Known
gaps" in [`API_CONTRACT.md`](API_CONTRACT.md)), Android client integration
(separate milestone).

## Running locally

Requires JDK 21. Two ways to get a database:

**Docker** (if available):
```bash
export JWT_SECRET=$(openssl rand -base64 48)   # >= 32 chars, HS256 requirement
docker compose up --build
```
App comes up on `http://localhost:8080`, Swagger UI at
`http://localhost:8080/swagger-ui/index.html`.

**No Docker** — this is how the build was actually verified during
development, on a machine with no Docker daemon: extract real PostgreSQL
binaries from the `io.zonky.test.postgres:embedded-postgres-binaries-windows-amd64`
Maven Central artifact (same mechanism the integration tests use
automatically) and run `initdb`/`pg_ctl` directly. See git history / ask if
you need the exact steps reproduced.

### Local JVM run against Dockerized dependencies only

```bash
docker compose up postgres redis kafka
export JWT_SECRET=$(openssl rand -base64 48)
./gradlew :bootstrap:bootRun
```

### Build and test

```bash
./gradlew build
```

No external database needed for this — unit tests use in-memory fakes and
the integration tests spin up their own real, embedded PostgreSQL
automatically (downloads once via Maven Central, then cached).

## Configuration

All runtime config is environment-variable driven (`bootstrap/src/main/resources/application.yml`) —
no secrets are hardcoded. Key variables:

| Variable | Default | Purpose |
|---|---|---|
| `JWT_SECRET` | *(none — required)* | HMAC signing key, >= 32 chars |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD` | `localhost` / `5432` / `rojan` / `rojan` / `rojan` | PostgreSQL |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka |
| `JWT_ACCESS_TTL_MINUTES` / `JWT_REFRESH_TTL_DAYS` | `15` / `30` | Token lifetimes |
