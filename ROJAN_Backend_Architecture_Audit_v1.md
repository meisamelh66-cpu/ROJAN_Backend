# ROJAN Backend — Architecture Audit v1

Status: **audit only.** No application code was changed to produce this document. Every claim below is sourced directly from the current codebase (file paths given inline); where the codebase doesn't decide something, it's listed under "Architecture Decisions Required" rather than assumed.

---

## 1. Dashboard API Contract

**Endpoint:** `GET /api/v1/dashboard/insights`
**Controller:** `api/src/main/kotlin/ai/rojan/backend/api/dashboard/DashboardController.kt`

| Aspect | Current implementation |
|---|---|
| HTTP method | `GET` |
| Required headers | `Authorization: Bearer <accessToken>` (no other required headers) |
| Authentication requirement | Required — endpoint is not in `SecurityConfig`'s `PUBLIC_ENDPOINTS`, so Spring Security's default `.anyRequest().authenticated()` applies |
| Request parameters | **None.** No `salonId` or any other query/path parameter |
| Tenant resolution method | Implicit, by ownership: `SalonRepository.findByOwnerId(callerId)` (`application/dashboard/GetDashboardInsightsUseCase.kt`) — `callerId` comes from the JWT subject via `CurrentUserResolver` |
| Response DTO | `DashboardInsightsResponse` (`api/src/main/kotlin/ai/rojan/backend/api/dashboard/DashboardDtos.kt`) — see §5 |

**Tenant resolution logic** (`GetDashboardInsightsUseCase.execute`):
```
ownedSalons = SalonRepository.findByOwnerId(callerId)
0 salons  -> SalonNotFoundException      -> 404
1 salon   -> use it
2+ salons -> AmbiguousSalonContextException -> 409
```

### Error responses

| Status | Thrown by | Mapped by | Body |
|---|---|---|---|
| `401 Unauthorized` | Spring Security itself, *before* the controller — missing/invalid/expired bearer token | `SecurityConfig`'s `HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)` | Empty body, `Content-Length: 0` (not an `ApiError` — this response never reaches `GlobalExceptionHandler`) |
| `404 Not Found` | `SalonNotFoundException` — caller owns zero salons | `GlobalExceptionHandler.handleNotFound` | `ApiError` JSON |
| `409 Conflict` | `AmbiguousSalonContextException` — caller owns more than one salon | `GlobalExceptionHandler.handleConflict` | `ApiError` JSON |

`ApiError` shape (`api/src/main/kotlin/ai/rojan/backend/api/common/GlobalExceptionHandler.kt`):
```json
{ "timestamp": "...", "status": 404, "error": "Not Found", "message": "...", "path": "...", "traceId": "..." }
```

---

## 2. Authentication Audit

**Files inspected:** `AuthController.kt`, `AuthenticateUserUseCase.kt`, `RegisterUserUseCase.kt`, `RefreshTokenUseCase.kt`, `JwtTokenProvider.kt`, `JwtAuthenticationFilter.kt`, `JwtProperties.kt`, `SecurityConfig.kt`, `RojanUserDetailsService.kt`.

### Login flow (`POST /api/v1/auth/login`)
1. `AuthController.login` → `AuthenticateUserUseCase.execute`.
2. Look up `User` by (trimmed, lowercased) email; `InvalidCredentialsException` (→ 401) if not found.
3. `PasswordEncoderPort.matches(rawPassword, user.passwordHash)` — BCrypt (`BCryptPasswordEncoderAdapter`); mismatch → `InvalidCredentialsException` (→ 401).
4. `user.active` check → `InactiveUserException` (→ 403) if deactivated.
5. Issue an access token + a refresh token (both JWTs, both signed the same way, distinguished only by a `type` claim).

### Token response format (`AuthResponse`)
```json
{
  "user": { "id": "...", "email": "...", "fullName": "...", "role": "CUSTOMER" },
  "accessToken": "...",
  "accessTokenExpiresAt": "2026-08-04T12:15:00Z",
  "refreshToken": "...",
  "refreshTokenExpiresAt": "2026-09-03T12:00:00Z"
}
```

### JWT claims (both access and refresh tokens)
| Claim | Source |
|---|---|
| `sub` | `user.id` (UUID string) |
| `email` | `user.email` |
| `role` | `user.role.name` (`CUSTOMER` / `MANAGER` / `SPECIALIST`) |
| `type` | `ACCESS` or `REFRESH` |
| `iss` | `rojan.security.jwt.issuer` (default `rojan-ai-backend`) |
| `iat` / `exp` | issued-at / expiry |
| `jti` | random UUID per token |

Signing: HMAC-SHA (`Keys.hmacShaKeyFor`, `io.jsonwebtoken` / jjwt), key = `rojan.security.jwt.secret` (env `JWT_SECRET`, **no default** — app refuses to start if unset).

### Expiration
- Access token: `JWT_ACCESS_TTL_MINUTES`, default **15 minutes**.
- Refresh token: `JWT_REFRESH_TTL_DAYS`, default **30 days**.

### Refresh token — exists
`POST /api/v1/auth/refresh` (`RefreshTokenUseCase`): validates the token, requires `type == REFRESH` (an access token presented here is rejected with `InvalidTokenException` → 401), re-loads the user, re-checks `active`, and issues a **new** access+refresh pair. There is no reuse/rotation tracking — a still-valid refresh token can be exchanged repeatedly, and the old one presented after a refresh is not explicitly invalidated (no server-side denylist/store for tokens at all).

### Session strategy
**Fully stateless.** `SessionCreationPolicy.STATELESS` in `SecurityConfig`; no server-side session, no cookie, no token store (Redis is wired in the stack but nothing in the auth path reads/writes it — confirmed by inspection of `JwtTokenProvider`/`RefreshTokenUseCase`, neither takes a `RedisTemplate` or any cache port). Every request is authenticated independently by `JwtAuthenticationFilter` (`infrastructure/.../security/JwtAuthenticationFilter.kt`), which parses the `Authorization: Bearer` header, validates the token, and populates `SecurityContextHolder` for that request only — nothing persists between requests. There is **no logout endpoint** (nothing to invalidate server-side, by design of pure stateless JWT — but this also means a leaked access token remains valid until it naturally expires).

*(Per instructions: the above is what exists today. No recommendation on whether the refresh-token or session model should change is made here.)*

---

## 3. Tenant Context Audit

**Files inspected:** `domain/salon/Salon.kt`, `domain/salon/SalonRepository.kt`, `domain/user/User.kt`, `api/common/CurrentUserResolver.kt`.

### How current user maps to Salon
- There is no `Tenant` entity. **`Salon` is the tenant.**
- `Salon.ownerId: UserId` is the only link between a `User` and a `Salon` — a single non-nullable foreign key, set once at `Salon.create()` and never reassigned (no `transferOwnership`-style method exists).
- `CurrentUserResolver.resolve(principal)` turns the JWT's `email` claim into a `UserId` by looking up `UserRepository.findByEmail`. From there, every salon-scoped use case calls `SalonRepository.findById(...)` or `findByOwnerId(...)` and compares `salon.ownerId == callerId` — ownership equality is the **entire** tenant-isolation mechanism in this codebase. There is no separate "membership" or "staff" table linking a `User` to a `Salon` they don't own (see Specialist note below).

### Multi-salon behavior
- `SalonRepository.findByOwnerId(ownerId): List<Salon>` — **the data model always supported one owner having many salons.** `CreateSalonUseCase` has no check preventing a user from creating a second, third, etc. salon.
- `GET /api/v1/salons/mine` (`SalonController.mine`) already returns the full list.
- Endpoints that take an explicit `salonId` path variable (e.g. `SalonBookingController`, `BookingController`) work correctly regardless of how many salons a user owns, since the salon is named explicitly and ownership is checked per-request.
- The new **`GET /api/v1/dashboard/insights`** (§1) is the first endpoint that resolves salon *implicitly*. It only works cleanly for an owner with **exactly one** salon; an owner with 0 or 2+ salons gets an error response (404 / 409) rather than a dashboard.

### Current limitations (as implemented, not as designed-for)
- **`Specialist` is a separate entity** (`domain/salon/Specialist.kt`) representing salon staff, but it is not linked to a `User` account anywhere in the codebase inspected — a specialist can be booked, but there's no mechanism for that staff member to log in and see "their" salon's data. Only the `Salon.ownerId` user can access owner-scoped endpoints.
- `UserRole` (`CUSTOMER` / `MANAGER` / `SPECIALIST`) is **not** consulted anywhere in tenant resolution or authorization (see §4) — it's informational metadata set at registration and stamped into the JWT `role` claim, nothing more.
- No per-salon timezone: all "today"/"this month" date bucketing (dashboard, insights) uses the **server's** local `LocalDate.now()` (documented inline in `GetDashboardInsightsUseCase`), not a salon-configured timezone — a salon in a different timezone than the server would see day/month boundaries shifted.
- No tenant-scoped configuration/branding storage beyond the `Salon` entity's own fields (name, description, phone, email, address).

---

## 4. Authorization Matrix

**Finding:** `UserRole` has exactly three values — `CUSTOMER`, `MANAGER`, `SPECIALIST` (`domain/user/User.kt`). **There is no `OWNER` role in the type system.** "Owner" is not a role; it's whichever user's `UserId` equals a given `Salon.ownerId`. Confirmed by codebase-wide search: zero uses of `@PreAuthorize`, `@Secured`, `hasRole`, `hasAuthority`, or any role-gated method security anywhere in the project. `SecurityConfig` only distinguishes "authenticated" vs. "not authenticated" at the HTTP layer; every fine-grained permission check below is done manually, in application-layer use cases or controllers, by comparing IDs.

| Role / relationship | Existing permissions (as enforced in code) | Dashboard Insights access | General API access |
|---|---|---|---|
| **OWNER** (`Salon.ownerId == caller`, not a `UserRole` value) | Full CRUD on their own salon(s), branches, service categories, services, specialists, working hours, schedules; confirm/cancel/complete any booking on their salon; list their salon's bookings | **Yes** — this is the only relationship the endpoint recognizes | Any endpoint whose authorization check is "salon owner" (most of `salon`, `schedule`, `booking` management endpoints) |
| **MANAGER** (`UserRole.MANAGER`) | *None inherent to the role itself.* A `MANAGER` who happens to also be a salon's `ownerId` gets OWNER permissions above — but nothing stops a `CUSTOMER`-role account from creating a salon and getting the exact same access (`CreateSalonUseCase` performs no role check) | Only if they own exactly one salon (i.e., only via the OWNER relationship, not via the role) | Same as any authenticated user, plus OWNER-tier access if they own a salon |
| **SPECIALIST** (`UserRole.SPECIALIST`) | *None found.* The `role` claim is not checked anywhere; a `Specialist` (staff) domain entity exists but isn't linked to a `User` account (see §3) | No — cannot own a salon any differently than any other role could, and there's no staff-access path to another salon's dashboard | Same as any authenticated user; not usable to represent "staff member of salon X" in any endpoint inspected |
| **CUSTOMER** (`UserRole.CUSTOMER`) | Create bookings, view/cancel/reschedule their own bookings (`booking.customerId == caller`), view their own bookings list | No, unless they also own a salon (role is not the gate — ownership is) | Same as any authenticated user, plus customer-scoped booking endpoints |

**Net finding:** access control in this codebase is **relationship-based** (booking-customer, salon-owner), **not role-based**. The `role` field is stored, returned in API responses, and put in the JWT, but it currently gates nothing.

---

## 5. Dashboard Response Contract

**Full response shape** (`DashboardInsightsResponse`, `api/dashboard/DashboardDtos.kt`):

```json
{
  "revenue": {
    "today": 150.00,
    "month": 3200.00,
    "growthRate": 18.50
  },
  "bookings": {
    "total": 42,
    "completed": 35,
    "cancelled": 4
  },
  "customers": {
    "newCustomers": 6,
    "returningCustomers": 12
  },
  "services": [
    { "name": "Haircut", "bookings": 20, "revenue": 2000.00 }
  ],
  "recommendations": [
    { "type": "REVENUE_GROWTH", "priority": "MEDIUM", "message": "درآمد شما نسبت به دوره قبل رشد داشته است." }
  ]
}
```

### Required fields
Every top-level key (`revenue`, `bookings`, `customers`, `services`, `recommendations`) is **always present** and non-null on a `200` response — none are declared nullable/optional in the Kotlin DTO. `services` and `recommendations` are arrays that may be **empty**, never `null`.

### Optional fields
**None** at the DTO level — there is no field the server may omit. (Nothing in `DashboardInsightsResponse` uses `?`.)

### Empty state
A salon with zero bookings this month returns `200` with:
- `revenue.today = 0`, `revenue.month = 0`, `revenue.growthRate = 0`
- `bookings = { total: 0, completed: 0, cancelled: 0 }`
- `customers = { newCustomers: 0, returningCustomers: 0 }`
- `services = []`
- `recommendations = []`

This is a normal `200`, not an error — verified by `GetDashboardInsightsUseCaseTest` and the bootstrap integration test.

### Error format
See §1's error table — all non-`401` errors use the shared `ApiError` shape; `401` is an empty body from the security layer, not `ApiError`.

### Field types
| Field | Type | Notes |
|---|---|---|
| `revenue.today` / `.month` | `BigDecimal` | sum of `Service.price` for `COMPLETED` bookings in range |
| `revenue.growthRate` | `BigDecimal` | percentage, 2 decimal places, month vs. previous month |
| `bookings.total/completed/cancelled` | `Long` | this-month counts, all statuses counted for `total` |
| `customers.newCustomers/returningCustomers` | `Int` | see §6/§3 for the "returning" definition (had a booking with this salon before this month) |
| `services[].bookings` | `Long`; `.revenue` | `BigDecimal` | only `COMPLETED` bookings this month, grouped by service |
| `recommendations[].type` | enum string (`RecommendationType`) | see §6 |
| `recommendations[].priority` | enum string (`LOW`/`MEDIUM`/`HIGH`) | |
| `recommendations[].message` | `String` | currently Persian-language, hardcoded per rule |

---

## 6. AI Recommendation Engine Audit

**Files:** `application/dashboard/RecommendationEngine.kt`, `application/dashboard/RuleBasedRecommendationEngine.kt`, wired via `api/config/DashboardUseCaseConfig.kt`.

### Architecture
```
GetDashboardInsightsUseCase
    builds SalonInsightMetrics (from Booking/Service data it already fetched)
        |
        v
    InsightEngine.generate(metrics)   <-- interface (the replaceable seam)
        |
        v
    RuleBasedRecommendationEngine     <-- current implementation, v1
        |
        v
    List<Recommendation>  -> attached to DashboardInsights.recommendations
```

`InsightEngine` is a plain Kotlin interface (`application` module — no Spring/framework dependency). `SalonInsightMetrics` is a dedicated input model, deliberately **not** the same type as the public `DashboardInsights`/API DTO, so the engine's contract doesn't change every time the response shape does.

### `RuleBasedRecommendationEngine` — current rules (all in one file, each independent, no external API/LLM call, pure deterministic Kotlin)

| Rule | Trigger | Type | Priority |
|---|---|---|---|
| Revenue growth | `revenueGrowthRate > 0` | `REVENUE_GROWTH` | `HIGH` if ≥20%, else `MEDIUM` |
| Revenue decline | `revenueGrowthRate < 0` | `REVENUE_DECLINE` | `HIGH` |
| Booking growth | `bookingsThisMonth > bookingsPreviousMonth` | `BOOKING_GROWTH` | `MEDIUM` |
| Booking decline | `bookingsThisMonth < bookingsPreviousMonth` | `BOOKING_DECLINE` | `MEDIUM` |
| Cancellation rate | current cancellation rate > previous period's **and** > 15%, only when both periods have data | `CANCELLATION_RATE` | `HIGH` |
| Low retention | `returning / (new+returning) < 20%` | `CUSTOMER_RETENTION_LOW` | `MEDIUM` |
| High retention | `returning / (new+returning) ≥ 50%` | `CUSTOMER_RETENTION_HIGH` | `LOW` |
| Top service | highest-revenue service this month (if any) | `SERVICE_PERFORMANCE` | `LOW` |

Each rule reads a slice of `SalonInsightMetrics` and returns `Recommendation?` — `null` means silent (no message), never an empty/placeholder string. `RuleBasedRecommendationEngine.generate()` is `listOfNotNull(...)` over all rules — order in that list is the order returned (no separate sorting by priority currently applied).

### Extension point for future LLM integration
Swapping v1 for a model-backed engine requires touching exactly one place: the `@Bean fun insightEngine(): InsightEngine` in `DashboardUseCaseConfig.kt`. `GetDashboardInsightsUseCase`, `DashboardController`, and the API response shape are all written against the `InsightEngine` interface and `Recommendation`/`SalonInsightMetrics` types — none of them know or care whether the implementation is rule-based or model-based. No other code path currently exists for AI/LLM calls (no HTTP client to an LLM provider, no prompt templates, no API key config anywhere in the codebase).

---

## 7. Security Review

| Area | Current state |
|---|---|
| **JWT security** | HMAC-SHA signing (`Keys.hmacShaKeyFor`), secret from `JWT_SECRET` env var with **no default** (`JwtProperties.secret` is non-nullable, no fallback — startup fails if unset). Issuer is validated on parse (`requireIssuer`). Access/refresh distinguished by a `type` claim, cross-checked at use (`RefreshTokenUseCase` rejects an access token; `JwtAuthenticationFilter` treats a refresh token as a non-`ACCESS` type and skips authenticating with it). No token revocation/denylist — a compromised token is valid until natural expiry (15 min access / 30 day refresh by default). |
| **CORS** | **Not configured anywhere.** No `CorsConfigurationSource` bean, no `.cors(...)` call in `SecurityConfig`'s `HttpSecurity` chain, no `@CrossOrigin` annotations found codebase-wide. Effectively: cross-origin browser requests to this API are not explicitly enabled. In production, `DEPLOYMENT.md`/`docker-compose.prod.yml` show Nginx reverse-proxying the app — if the Web frontend is served from the same origin/domain via Nginx, this is moot; if it's served from a different origin, CORS would need to be added. |
| **CSRF** | Explicitly disabled (`.csrf { it.disable() }` in `SecurityConfig`). Consistent with a stateless, header-bearer-token API with no cookie-based session — this is standard practice for this auth model, not an oversight, given there's no session cookie for a CSRF attack to ride on. |
| **Secret handling** | `JWT_SECRET`, `DB_PASSWORD`, `REDIS_PASSWORD` all environment-variable driven, no hardcoded values found in source or committed config (`application.yml` only references `${...}` placeholders). `.env` is templated via `.env.example`, real `.env` is gitignored per `DEPLOYMENT.md`'s checklist. |
| **Rate limiting** | **None found.** No Bucket4j, no `RateLimiter`, no request-throttling filter anywhere in the dependency tree or code. `/api/v1/auth/login` and `/api/v1/auth/register` are unauthenticated-by-necessity public endpoints with no brute-force/abuse protection at the application layer (nothing preventing credential-stuffing or registration spam beyond whatever, if anything, sits in front at the network/proxy level — Nginx config was not audited for rate-limit directives as part of this pass). |
| **Public endpoints** | Exact list, `SecurityConfig.PUBLIC_ENDPOINTS`: `/api/v1/auth/**`, `/api/v1/public/**`, `/actuator/health`, `/actuator/health/**`, `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html`. Everything else requires a valid bearer token (`.anyRequest().authenticated()`). |
| **Public Website API — implementation note** | `PublicWebsiteController` (`GET /api/v1/public/{tenantSlug}/website`) is currently **hardcoded/stub data** — it echoes back the `tenantSlug` path variable but does not look up a real `Salon` by slug, and always returns the same static `name`/`description`/`status` fields regardless of which tenant is requested. There is no `slug` field on the `Salon` entity in the domain model inspected. This is a finding, not a code change — flagged under §9 below. |
| **Actuator exposure** | `management.endpoints.web.exposure.include: health,info` only (base `application.yml`); `show-details: when-authorized` in dev, tightened to `never` in prod (`application-prod.yml`) — no full actuator surface (env, beans, etc.) exposed in either profile. |
| **Password storage** | BCrypt via `BCryptPasswordEncoderAdapter` (Spring Security's standard adapter) — not reversible, salted per-hash by BCrypt's own mechanism. |

---

## 8. Deployment Architecture

*(Cross-referenced against the existing `DEPLOYMENT.md`, which documents this in more depth — summarized here for audit completeness.)*

### Docker setup
- **Dev** (`docker-compose.yml`, repo root): `postgres`, `redis`, `kafka`, `app` — ports published to host, no TLS, single-file compose.
- **Prod** (`docker-compose.prod.yml`): `postgres`, `redis`, `app`, `cert-init`, `nginx`, `certbot` — **no Kafka service** (deliberate; see `application-prod.yml`'s `management.health.kafka.enabled: false` and the comment block at the top of `docker-compose.prod.yml`). Postgres/Redis are internal-only (not published to host); only Nginx's 80/443 are host-exposed.

### Build process
`Dockerfile` **does not run Gradle inside the image.** It only:
```dockerfile
COPY bootstrap/build/libs/bootstrap-0.1.0-SNAPSHOT.jar app.jar
```
The jar must already exist on the host (or the server's build context directory) *before* `docker compose build app` runs — built via `./gradlew :bootstrap:bootJar` (or `gradlew.bat` on Windows) outside Docker entirely. This was the root cause of at least one deployment incident during this project's history (a stale jar on the server producing 401s despite a locally-fixed `SecurityConfig.kt`).

### Jar deployment flow (as currently practiced in this project, no CI/CD exists)
1. `./gradlew clean build` (compiles + runs full test suite) → `./gradlew bootJar` locally.
2. `scp` the resulting `bootstrap/build/libs/bootstrap-0.1.0-SNAPSHOT.jar` to the server at the same relative path inside the repo checkout.
3. `docker compose build app` (re-copies the new jar into the image) → `docker compose up -d --no-deps app`.
4. Verify via `docker compose ps app` (health) and a smoke-test `curl`.

`scripts/deploy.sh` (per `DEPLOYMENT.md`) automates steps 3-4 plus directory-ownership provisioning, but does **not** build or transfer the jar itself.

### Database migration strategy
Flyway, forward-only. `spring.flyway.enabled: true`, `locations: classpath:db/migration`, `baseline-on-migrate: true`. `spring.jpa.hibernate.ddl-auto: validate` — **Hibernate never mutates schema**; Flyway (`infrastructure/src/main/resources/db/migration/V1..V4__*.sql`) is the single source of truth. No down-migrations exist; `scripts/rollback.sh` explicitly reverts application code only, not schema (documented boundary in `DEPLOYMENT.md` §8) — a schema-incompatible rollback requires restoring from `scripts/backup.sh`'s `pg_dump` output.

### Health checks
- App: `HEALTHCHECK` in `Dockerfile` (image-level) and `docker-compose.prod.yml` (compose-level, takes precedence) both hit `curl -f http://localhost:8080/actuator/health`.
- Postgres/Redis: compose healthchecks (`pg_isready`, `redis-cli ping`).
- Nginx: hits a static `/.well-known/healthcheck` location, independent of app health, so a legitimate app restart doesn't falsely mark Nginx unhealthy.
- `app`'s `depends_on` in both compose files: `postgres: condition: service_healthy`, `redis: condition: service_healthy` — Kafka is intentionally not a dependency in prod (absent entirely) and was removed from `depends_on` in dev during this project's history.

---

## Architecture Decisions Required

Not implemented in code today; genuinely open questions this audit surfaces rather than answers:

1. **Should "salon owner" become a real, enforced role/permission concept?** Today any authenticated user of any `UserRole` can call `POST /api/v1/salons` and become a de facto owner (§3, §4) — `CreateSalonUseCase` performs no role check. If ROJAN intends `MANAGER` to be the only role that can own a salon, that check does not currently exist anywhere.
2. **Multi-salon dashboard UX.** `GET /api/v1/dashboard/insights` 409s for an owner with 2+ salons (§1, §3). No `salonId` disambiguation mechanism (query param, header, or otherwise) has been (re-)added — is 409-and-stop the intended permanent behavior, or does Web need a salon-picker + an explicit-selection endpoint variant?
3. **Specialist/staff login access.** The `Specialist` domain entity is not linked to any `User` account (§3, §4) — is staff-level login/access to a salon's data (bookings, schedule, dashboard) in scope for a future milestone, and if so, how does a `Specialist` map to a `User`?
4. **CORS policy.** No CORS configuration exists (§7). If ROJAN Web is served from a different origin than the API (rather than same-origin via Nginx), an explicit CORS policy (allowed origins, credentials mode) needs to be decided and added.
5. **Rate limiting.** None exists anywhere in the stack as inspected (§7), particularly relevant for the unauthenticated `/api/v1/auth/login` and `/api/v1/auth/register` endpoints and the public `/api/v1/public/**` website API.
6. **Refresh-token revocation/rotation.** Refresh tokens have no server-side store, so there is no way to revoke one before natural expiry (e.g., on logout, password change, or suspected compromise) (§2). Already flagged as a known gap in `API_CONTRACT.md` per `DEPLOYMENT.md`'s reference to it — restated here since it's directly relevant to the auth audit.
7. **`PublicWebsiteController` real data wiring.** Currently returns static/stub content regardless of `tenantSlug` (§7) — no `Salon.slug` field exists in the domain model. Whether/how a salon gets a public slug, and how this controller should look one up, is undecided.
8. **Per-salon timezone.** All date-bucketed dashboard/insight logic uses the server's local date (§3) — whether salons need their own timezone for "today"/"this month" boundaries is undecided.
9. **CI/CD and jar-transport automation.** No CI pipeline or container registry (§8) — the jar is built locally and `scp`'d by hand today, which has already caused at least one stale-deployment incident in this project's history. Whether to introduce CI/CD is unresolved.
