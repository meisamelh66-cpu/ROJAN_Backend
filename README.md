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

## Implemented so far (foundation milestone)

- `User` domain entity + `UserRole` (`CUSTOMER`, `MANAGER`, `SPECIALIST`)
- Auth use cases: register, login, refresh — all pure Kotlin, unit-tested
  against in-memory fakes (no Spring context required)
- JWT access/refresh tokens (`io.jsonwebtoken`, HS256), stateless Spring
  Security filter chain
- PostgreSQL persistence via Spring Data JPA + a repository-pattern adapter;
  Flyway migration `V1__init_schema.sql` owns the schema (`ddl-auto: validate`,
  Hibernate never mutates the schema itself)
- `POST /api/v1/auth/register`, `/login`, `/refresh`, `GET /api/v1/users/me`
- OpenAPI/Swagger UI at `/swagger-ui.html`
- Redis + Kafka: connection/config beans only, ready for the first real
  caching/eventing use case to consume

## Not yet built

Booking/salon/service domain, staff/specialist accounts beyond the role enum,
Kafka topics/consumers, Redis-backed caching, integration tests (e.g.
Testcontainers against a real Postgres), CI pipeline, refresh-token
revocation/rotation storage. Extend additively through the same layering —
see `CLAUDE.md`-style conventions in `ROJAN_DesignLab` for the pattern this
repo follows (frozen baselines, confirm before architecture changes).

## Running locally

Requires JDK 21 and Docker. **This environment did not have either
installed when this repo was scaffolded, so the build below has not been
executed/verified here — verify it on a machine with JDK 21 + Docker before
relying on it.**

### Full stack via Docker Compose

```bash
export JWT_SECRET=$(openssl rand -base64 48)   # >= 32 chars, HS256 requirement
docker compose up --build
```

App comes up on `http://localhost:8080`, Swagger UI at
`http://localhost:8080/swagger-ui.html`.

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
