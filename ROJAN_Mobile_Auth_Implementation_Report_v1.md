# ROJAN Mobile Authentication — Phase 1 Implementation Report v1

**Scope:** Backend OTP Foundation, per "ROJAN Mobile Authentication Phase 1 Implementation Order" (Architecture Approved, following `ROJAN_Mobile_Auth_Architecture_v1.md`).
**Status:** Complete. `./gradlew clean build` — **BUILD SUCCESSFUL**, 188/188 tests passing, 0 failures, 0 errors.

---

## 1. What was built

Three new endpoints under `/api/v1/auth`:

| Endpoint | Purpose |
|---|---|
| `POST /otp/request` | Issue a 6-digit OTP to a mobile number (E.164), subject to per-phone and per-IP rate limits |
| `POST /otp/resend` | Same as `/otp/request` — same use case, same rate limits, separate route for client clarity |
| `POST /otp/verify` | Verify a code; on success, returns the **existing `AuthResponse` shape** (access/refresh token pair). Creates the account on first successful verification for a new phone number, reuses the existing account otherwise |

Identity resolution moved to `JWT sub = userId` throughout (filter, `CurrentUserResolver`, `UserController.me()`, `RojanUserDetailsService`) — email is no longer required to authenticate a request. Email-based login (`/auth/login`, `/auth/register`) is untouched and still works for existing accounts; `User.email`/`User.passwordHash` are now nullable to allow phone-only accounts, guarded by a DB check constraint (`email IS NOT NULL OR phone_number IS NOT NULL`).

**Not changed, as instructed:** the `AuthResponse` JSON shape, the Dashboard API, the Session model, the JWT signing/verification pipeline.

## 2. Files changed

### Domain (new)
- `domain/auth/PhoneNumber.kt` — E.164-validated value type
- `domain/auth/OneTimePassword.kt` — immutable entity: issue/reconstitute, expiry check, attempt decrement
- `domain/auth/OtpRepository.kt` — port
- `domain/common/OtpDomainExceptions.kt` — `InvalidOtpException`, `OtpRateLimitExceededException`, `OtpVerifyRateLimitExceededException`

### Domain (modified)
- `domain/user/User.kt` — `email`/`passwordHash` now nullable, added `phoneNumber`, added `registerWithPhone()` factory
- `domain/user/UserRepository.kt` — added `findByPhoneNumber` / `existsByPhoneNumber`

### Application (new)
- `application/auth/OtpHashing.kt` — SHA-256 code hashing
- `application/auth/OtpPolicy.kt` — framework-free OTP config carrier
- `application/auth/RequestOtpUseCase.kt` — serves both `/otp/request` and `/otp/resend`
- `application/auth/VerifyOtpUseCase.kt` — verify + issue-or-reuse account + token pair
- `application/port/SmsProviderPort.kt` — vendor-agnostic SMS output port
- `application/port/RateLimiterPort.kt` — generic key/limit/window rate-limit port

### Application (modified)
- `application/port/TokenProviderPort.kt` — `TokenSubject.email` is now nullable
- `application/auth/AuthenticateUserUseCase.kt` — rejects phone-only accounts (no `passwordHash`) cleanly as invalid credentials instead of NPE-ing

### Infrastructure (new)
- `infrastructure/otp/RedisOtpRepository.kt` — Redis-backed, native TTL
- `infrastructure/ratelimit/RedisRateLimiter.kt` — fixed-window counter via `INCR`+`EXPIRE`
- `infrastructure/sms/LoggingSmsProvider.kt` — **dev-only placeholder**, logs instead of sending (see Blockers)
- `infrastructure/security/OtpProperties.kt` — `@ConfigurationProperties(prefix = "rojan.security.otp")`
- `infrastructure/db/migration/V5__mobile_authentication.sql` — nullable email/password_hash, `phone_number` column + unique index, identity check constraint

### Infrastructure (modified)
- `infrastructure/security/JwtTokenProvider.kt` — `sub` = userId (already was), email claim now optional, new optional `phone` claim
- `infrastructure/security/RojanUserDetailsService.kt` — loads by userId instead of email
- `infrastructure/security/JwtAuthenticationFilter.kt` — looks up principal by `subject.userId`
- `infrastructure/security/SecurityPropertiesConfig.kt` — registers `OtpProperties`, adds the `OtpPolicy` bean (bridges Spring config into the framework-free `application` module — see note below)
- `infrastructure/persistence/user/UserJpaEntity.kt`, `UserSpringDataRepository.kt`, `UserRepositoryAdapter.kt` — nullable email/password columns, new `phone_number` column + lookups

### API (new)
- `api/auth/OtpDtos.kt` — `OtpRequestRequest`, `OtpResendRequest`, `OtpVerifyRequest`, `OtpIssuedResponse`

### API (modified)
- `api/auth/AuthDtos.kt` — `UserResponse.email` nullable, added `UserResponse.phoneNumber`
- `api/auth/AuthController.kt` — 3 new endpoints wired
- `api/common/CurrentUserResolver.kt` — resolves by userId, not email
- `api/common/GlobalExceptionHandler.kt` — new `429 TOO_MANY_REQUESTS` handler + `INVALID_OTP`/`OTP_REQUEST_RATE_LIMITED`/`OTP_VERIFY_RATE_LIMITED` error codes
- `api/config/UseCaseConfig.kt` — wires `RequestOtpUseCase`/`VerifyOtpUseCase`
- `api/user/UserController.kt` — `me()` fixed to resolve by userId (was broken by the identity change — see §4)
- `bootstrap/application.yml` — `rojan.security.otp.*` section, all overridable via env vars

### Tests (new)
- `application/auth/OtpTestFixtures.kt` — shared in-memory `OtpRepository`/`SmsProviderPort`/`RateLimiterPort`/`UserRepository` fakes
- `application/auth/RequestOtpUseCaseTest.kt` — 5 tests
- `application/auth/VerifyOtpUseCaseTest.kt` — 7 tests

### Tests (modified — fixed to compile against the widened `UserRepository` interface)
- `application/auth/RegisterUserUseCaseTest.kt`, `AuthenticateUserUseCaseTest.kt`, `RefreshTokenUseCaseTest.kt`
- `application/salon/SalonTestFixtures.kt`

*(The working tree also has unrelated uncommitted changes from earlier tickets this session — Dashboard API, Booking hardening, deployment/nginx config, `SecurityConfig.kt`, `OpenApiConfig.kt` — none of those were touched by this Phase 1 work and they're out of scope for this report.)*

## 3. A design note worth flagging explicitly

`api` has no compile-time dependency on `infrastructure` (by design — `bootstrap` is the only module that wires both together at runtime via component scanning). My first pass put the `OtpPolicy` bean-construction in `api/config/UseCaseConfig.kt`, referencing `infrastructure.security.OtpProperties` directly — this failed to compile (`Unresolved reference 'infrastructure'`) and would have been a real architecture-boundary violation had it compiled. Fixed by moving that bean into `infrastructure/security/SecurityPropertiesConfig.kt` instead, where `OtpProperties` actually lives; `api`'s `UseCaseConfig` just consumes the resulting `OtpPolicy` bean by type, same as every other port it wires. Caught by the compiler, not by inspection — a good argument for running the build early rather than late.

## 4. A real bug this phase's identity change would have caused

`UserController.me()` (`GET /api/v1/users/me`) still did `userRepository.findByEmail(Email(principal.username))`. Since `principal.username` is now the userId (per this phase's `sub = userId` requirement), that call would have thrown `IllegalArgumentException` (invalid email format) on every request, on every account — including existing email/password users, who this phase explicitly wasn't supposed to break. Found by grepping for other `principal.username` call sites before considering the identity-resolution work done, not by the build (Kotlin's type system didn't catch it — `Email(String)` accepts any string at the type level). Fixed to resolve by `UserId` instead, matching every other controller's `CurrentUserResolver` pattern.

## 5. Tests

| Suite | Tests | Result |
|---|---|---|
| `RequestOtpUseCaseTest` | 5 | ✅ issues+hashes+sends code, rejects non-E.164 input, per-phone rate limit, per-IP rate limit, IP check skipped when no IP available |
| `VerifyOtpUseCaseTest` | 7 | ✅ verify+create account, verify+reuse existing account, wrong code decrements attempts, attempts-exhausted deletes code, expired code rejected, no-code-requested rejected, verify rate limit |
| Full repo suite | 188 | ✅ 0 failures, 0 errors — includes `bootstrap`'s embedded-Postgres integration tests, which ran Flyway against a real (ephemeral) Postgres instance, exercising `V5__mobile_authentication.sql` for real, not just as a syntax check |

`./gradlew clean build` output: **BUILD SUCCESSFUL**, `bootJar` produced as part of the standard `bootstrap:build` chain (no separate `bootJar` step was necessary or run).

## 6. Remaining blockers / gaps (explicit, not hidden)

1. **`LoggingSmsProvider` is a dev-only placeholder.** It logs the OTP code instead of sending an SMS — per this phase's own instruction ("do not hardcode SMS vendor"), no real vendor was selected. **Must not reach production as-is**: logging a live OTP is a real (if narrow) exposure. Swapping in a real vendor is one new `SmsProviderPort` adapter + one DI registration — nothing above the port changes.
2. **Redis-backed adapters (`RedisOtpRepository`, `RedisRateLimiter`) are not integration-tested against a live Redis.** No embedded/test Redis exists in this repo's test environment (checked before writing the plan, not assumed). Covered instead by in-memory fakes at the use-case level, matching this codebase's existing convention for every other repository port — but that means the actual Redis serialization/TTL/`INCR` behavior has zero automated coverage. Worth a manual smoke test against a real Redis instance before shipping.
3. **`UserRole.CUSTOMER` is a judgment call** for accounts auto-created on first OTP verification — the architecture doc didn't specify a role for this path. Low-risk because no RBAC exists anywhere in this codebase today (confirmed earlier this session), but worth explicit sign-off if that changes later.
4. **New phone-only accounts get the fallback name "ROJAN User"** if the Owner App doesn't pass `fullName` on the first successful `/otp/verify` call. Worth confirming the mobile client always sends it on first-time signup.
5. **No MockMvc/HTTP-layer integration test** exercises the 3 new endpoints through Spring Security + validation end-to-end; coverage is at the application-layer use-case level, per the ticket's Task 8 list. If full HTTP-layer confidence is wanted before the Owner App integrates, that's a follow-up.
6. **OTP policy defaults (TTL, attempt limits, rate-limit windows) are the architecture proposal's stated numbers, not empirically tuned** — all overridable per-environment via env vars (`OTP_TTL_SECONDS`, etc.) without a code change.
7. **Nothing on the Owner App (.NET/WPF) side changed** — this phase was explicitly backend-only. The Mobile-First flow (Mobile Number → OTP → JWT → Secure Storage → Dashboard) still needs its client-side implementation as a separate, not-yet-started phase.
