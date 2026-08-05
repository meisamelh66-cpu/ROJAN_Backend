# ROJAN Auth API Rate Limiting — Implementation Report v1

**Scope:** Phase 1.1 only, per the approved `ROJAN_Public_Launch_Hardening_Plan_v1.md` §1.1. `/auth/login`, `/auth/register`, `/auth/refresh`. No other hardening item started.
**Repository:** `ROJAN_Backend` (only repository touched).
**Requirements honored:** additive changes only, existing wire contracts unchanged, `RateLimiterPort`/`RedisRateLimiter` pattern reused exactly as OTP already established it, tests added.

---

## Executive Summary

All three endpoints named in the plan now enforce rate limits, using the exact same `RateLimiterPort` abstraction and env-var-driven `@ConfigurationProperties` → framework-free policy layering that `/auth/otp/request`/`/auth/otp/verify` already established. No request/response DTO changed; the only wire-visible addition is a new, documented 429 response on each of the three endpoints, following the identical `ApiError` shape and Swagger-doc convention the OTP endpoints already use.

While implementing this, running the full test suite surfaced a real gap: the backend's own full-Spring-context integration suite had never previously exercised `RateLimiterPort` over real HTTP (no integration test calls `/auth/otp/*`), so it had never needed a reachable Redis. Making `/auth/login`/`/auth/register`/`/auth/refresh` — endpoints nearly every integration test class uses as its own setup step — depend on `RateLimiterPort` for the first time exposed that gap immediately. This is resolved the same way this codebase already resolves the identical problem for its SMS provider (`RealSmsProviderAdapter` vs. `LoggingSmsProvider`, `@Profile("!test")`/`@Profile("test")`): `RedisRateLimiter` is now `@Profile("!test")`, and a new `InMemoryRateLimiter` (`@Profile("test")`) provides a real, correct, dependency-free equivalent for the test profile. Full details in §4.

**Final state:** 254/254 backend tests passing (unit + full integration suite), 0 failures, clean `./gradlew build`.

---

## 1. What Changed, Per Endpoint

### `POST /auth/login`
- Rate-limited by two independent budgets, both consumed on every attempt regardless of which one is already exhausted (same "consume every applicable budget" rule `RequestOtpUseCase` already follows):
  - Per normalized **submitted email** (`auth:login:email:<email>`) — keyed by the email as typed, not a DB-lookup result, so a wrong-password guess and a nonexistent-email probe consume the same budget and can't be distinguished by timing/behavior (mirrors `InvalidCredentialsException`'s existing single-message-for-both-cases reasoning).
  - Per **caller IP** (`auth:login:ip:<ip>`), skipped only if no IP is available (never true in production; only possible for a test/internal caller).
- Exceeding either throws `LoginRateLimitExceededException` → `429 LOGIN_RATE_LIMITED`.

### `POST /auth/register`
- Rate-limited per **caller IP** only (`auth:register:ip:<ip>`) — no per-email budget, since `EmailAlreadyRegisteredException` already rejects a repeat email regardless of rate, and a not-yet-registered email has no prior identity to key against.
- Exceeding it throws `RegisterRateLimitExceededException` → `429 REGISTER_RATE_LIMITED`, checked **before** any repository/password work.

### `POST /auth/refresh`
- Rate-limited per **caller IP** only (`auth:refresh:ip:<ip>`), checked **before** the token is even validated — a flood of garbage tokens still consumes the budget rather than skipping it because validation would fail anyway.
- Exceeding it throws `RefreshRateLimitExceededException` → `429 REFRESH_RATE_LIMITED`.

All three new exceptions extend the existing `DomainException` base, are handled by a new `GlobalExceptionHandler.handleAuthRateLimitExceeded` (sibling to the existing `handleOtpRateLimitExceeded`, same `429`/`ApiError` shape, same `errorCodeFor` pattern), and are documented on each endpoint's `@ApiResponses` exactly like the OTP endpoints' own 429 documentation.

---

## 2. Design Decisions

| Decision | Reasoning |
|---|---|
| One `AuthRateLimitPolicy`/`AuthRateLimitProperties` pair covering all three endpoints, not three separate policy classes | Mirrors `OtpPolicy`/`OtpProperties`'s own shape - one policy per auth sub-area, covering multiple related operations (OTP's covers request/resend/verify; this one covers login/register/refresh). |
| Single limit+window per key (not OTP's short+long dual-window) | OTP's dual-window shape was a specific product spec ("the Mobile Auth Architecture proposal's stated defaults"); no equivalent spec exists for login/register/refresh, so a single, simpler window per identifier is the minimal correct scope - additive without inventing unrequested complexity. |
| Login keyed by *submitted* email, not a post-lookup result | Deciding the rate-limit key from whether the account exists would let an attacker distinguish "unknown email" from "wrong password" by which rate-limit budget got consumed - a new enumeration side-channel this class doesn't otherwise have. Keying by the raw input avoids it. |
| Register has no per-email budget | Redundant with `EmailAlreadyRegisteredException`'s existing 409; per-IP is the meaningful control for registration spam. |
| Defaults: login 5/email + 20/IP per 5 min; register 5/IP per hour; refresh 30/IP per 5 min | Reasonable starting points, explicitly not empirically tuned - same framing `OtpProperties`' own doc comment uses for its defaults. Every value is env-var-overridable without a code change. |
| `callerIp` is an optional (`= null`), not required, field on each `*Command` | Keeps every existing call site of these commands (production and test) source-compatible - additive by construction, not just by intent. `AuthController` is the only real caller and always supplies the real one via `HttpServletRequest.remoteAddr`. |

---

## 3. Config Reference

Added to `bootstrap/src/main/resources/application.yml` under `rojan.security.auth`, following the exact env-var-with-default convention every other `rojan.security.*` block already uses:

| Property | Env var | Default |
|---|---|---|
| `login-limit-per-email-window` | `AUTH_LOGIN_LIMIT_PER_EMAIL_WINDOW` | 5 |
| `login-limit-per-ip-window` | `AUTH_LOGIN_LIMIT_PER_IP_WINDOW` | 20 |
| `login-window-seconds` | `AUTH_LOGIN_WINDOW_SECONDS` | 300 |
| `register-limit-per-ip-window` | `AUTH_REGISTER_LIMIT_PER_IP_WINDOW` | 5 |
| `register-window-seconds` | `AUTH_REGISTER_WINDOW_SECONDS` | 3600 |
| `refresh-limit-per-ip-window` | `AUTH_REFRESH_LIMIT_PER_IP_WINDOW` | 30 |
| `refresh-window-seconds` | `AUTH_REFRESH_WINDOW_SECONDS` | 300 |

Not added to `.env.example` - consistent with the OTP block's own precedent (its env vars aren't listed there either), since every value has a safe default and none are `:?required` like `JWT_SECRET`/`DB_PASSWORD`.

---

## 4. Test Infrastructure Finding (Discovered, Not Pre-Planned)

Running `./gradlew test` after the initial implementation produced **30 failures across all 8 `bootstrap` integration test classes** (42 tests total in that module). Root-caused directly, not guessed:

1. Every failure's stack trace bottomed out in `RedisConnectionFailureException` / "Unable to connect to Redis" / "Connection refused" - confirmed by grepping the actual test-result XML, not inferred.
2. Confirmed no Redis is reachable in this environment (`localhost:6379` closed; no Docker, no WSL installed here) - this is a property of the sandbox this work was done in, not of the target deployment (which already runs Redis via `docker-compose.prod.yml`).
3. Confirmed via `grep` that **no existing integration test calls `/auth/otp/request`, `/auth/otp/verify`, or `/auth/otp/resend`** - meaning `RateLimiterPort`/`RedisRateLimiter` had never been exercised by the full-Spring-context suite before, only by unit tests using the existing `RecordingRateLimiter` fake. OTP's own real-Redis behavior has apparently never been integration-tested end-to-end either - a pre-existing gap this work exposed, not introduced.
4. Confirmed via `git stash` that the exact same test class passed cleanly on the original, unmodified code - proving this was a real regression caused by this change's design (making 3 heavily-used-as-setup endpoints newly depend on `RateLimiterPort`), not a flaky or pre-existing failure.

**Resolution**, following this codebase's own established precedent for exactly this situation (`RealSmsProviderAdapter` `@Profile("!test")` vs. `LoggingSmsProvider` `@Profile("test")`, documented in that class's own doc comment):

- `RedisRateLimiter` is now annotated `@Profile("!test")`.
- A new `InMemoryRateLimiter` (`@Profile("test")`) implements `RateLimiterPort` with a real, correct, `ConcurrentHashMap`-based fixed-window counter - atomic per key (via `compute`), so it stays correct under the genuine concurrent load `BookingConflictConcurrencyIntegrationTest` generates in the same Spring context.
- `bootstrap/src/test/resources/application-test.yml` additionally raises the four `*-limit-per-*-window` values to a generous 10,000 for the `test` profile - the integration suite legitimately calls `/auth/register`+`/auth/login` dozens of times per run (each of the 8 flow-test classes registers its own fixture user as setup), which is real test volume, not abuse; the production defaults in `application.yml` are untouched.

This is not a workaround that weakens the feature - production still uses the real, Redis-backed limiter with the real, conservative defaults. It is the same test/production adapter split this codebase already uses for its other external dependency (SMS), now extended to cover the one it didn't yet need before this change.

---

## 5. Files Changed

**New:**
- `domain/src/main/kotlin/ai/rojan/backend/domain/common/AuthRateLimitExceptions.kt` — `LoginRateLimitExceededException`, `RegisterRateLimitExceededException`, `RefreshRateLimitExceededException`.
- `application/src/main/kotlin/ai/rojan/backend/application/auth/AuthRateLimitPolicy.kt` — framework-free policy.
- `infrastructure/src/main/kotlin/ai/rojan/backend/infrastructure/security/AuthRateLimitProperties.kt` — Spring-bound config.
- `infrastructure/src/main/kotlin/ai/rojan/backend/infrastructure/ratelimit/InMemoryRateLimiter.kt` — test-profile `RateLimiterPort` (see §4).

**Modified (production code):**
- `application/src/main/kotlin/ai/rojan/backend/application/auth/AuthenticateUserUseCase.kt`, `RegisterUserUseCase.kt`, `RefreshTokenUseCase.kt` — rate-limit enforcement + optional `callerIp` on each command.
- `api/src/main/kotlin/ai/rojan/backend/api/auth/AuthController.kt` — threads `HttpServletRequest.remoteAddr` into all three commands; documents the new 429 response.
- `api/src/main/kotlin/ai/rojan/backend/api/common/GlobalExceptionHandler.kt` — new `429` handler + `errorCodeFor` entries for the three new exceptions.
- `api/src/main/kotlin/ai/rojan/backend/api/config/UseCaseConfig.kt` — threads `RateLimiterPort`/`AuthRateLimitPolicy` into the three use-case bean definitions.
- `infrastructure/src/main/kotlin/ai/rojan/backend/infrastructure/security/SecurityPropertiesConfig.kt` — registers `AuthRateLimitProperties`, bridges it to `AuthRateLimitPolicy`.
- `infrastructure/src/main/kotlin/ai/rojan/backend/infrastructure/ratelimit/RedisRateLimiter.kt` — `@Profile("!test")` (see §4).
- `bootstrap/src/main/resources/application.yml` — new `rojan.security.auth.*` block.
- `bootstrap/src/test/resources/application-test.yml` — test-profile limit overrides (see §4).

**Modified (tests):**
- `application/src/test/kotlin/ai/rojan/backend/application/auth/AuthenticateUserUseCaseTest.kt` — constructor update + 5 new tests.
- `application/src/test/kotlin/ai/rojan/backend/application/auth/RegisterUserUseCaseTest.kt` — constructor update + 3 new tests.
- `application/src/test/kotlin/ai/rojan/backend/application/auth/RefreshTokenUseCaseTest.kt` — constructor update + 3 new tests.
- `application/src/test/kotlin/ai/rojan/backend/application/auth/OtpTestFixtures.kt` — added shared `testAuthRateLimitPolicy` fixture (reuses the existing `RecordingRateLimiter`).

**Untouched by design:** `RequestOtpUseCase`, `VerifyOtpUseCase`, `OtpPolicy`, `OtpProperties`, and every non-auth endpoint/use case. No request or response DTO (`LoginRequest`, `RegisterRequest`, `RefreshRequest`, `AuthResponse`, `UserResponse`, `ApiError`) changed shape.

---

## 6. Test Evidence

```
./gradlew build

domain:test............... 51/51 passed
application:test..........157/157 passed   (11 new: rate-limit unit tests)
infrastructure:test.........4/4 passed
bootstrap:test.............42/42 passed    (was 30 failing before the §4 fix)
------------------------------------------
Total: 254/254 passed, 0 failures, 0 errors
Full ./gradlew build: clean (no lint/quality-gate failures)
```

**New unit test coverage (11 tests, all following the existing `RecordingRateLimiter`/fake-repository conventions already established in this test package):**
- `AuthenticateUserUseCaseTest`: per-email limit exceeded, per-IP limit exceeded, null caller IP doesn't skip the email check, rate limit is keyed by the submitted email even for an unknown account (verifies the enumeration-avoidance design decision in §2 directly, not just indirectly).
- `RegisterUserUseCaseTest`: per-IP limit exceeded (rejected before touching the repository), null caller IP skips the check rather than throwing, rate limit is keyed by IP not email.
- `RefreshTokenUseCaseTest`: per-IP limit exceeded (rejected before the token is even validated), null caller IP skips the check, rate limit is keyed by IP.

**Existing test suite:** every previously-passing test across all 6 backend modules still passes - no existing assertion was changed, only constructor calls updated to supply the new (test-fake) collaborators, exactly as expected for an additive dependency injection change.

---

## 7. Contract Compatibility Confirmation

- No request or response DTO field added, removed, or renamed.
- No existing endpoint's success-path behavior changed.
- The only client-visible addition is a `429` status with the existing `ApiError` body shape (`timestamp`, `status`, `error`, `errorCode`, `message`, `path`, `traceId`) - the same shape every other error response already uses, safe for any existing client to handle via its existing generic-error-response path even without specific 429 handling.
- Swagger/OpenAPI docs (`@ApiResponses`) updated on all three endpoints to describe the new response, matching the existing OTP endpoints' own documentation pattern exactly.

---

## 8. Scope Discipline

Only Phase 1.1 was implemented. No CORS, refresh-token rotation, audit logging, Owner App changes, Nginx-layer limiting, monitoring, or any other item from `ROJAN_Public_Launch_Hardening_Plan_v1.md` was started or touched, per explicit instruction.
