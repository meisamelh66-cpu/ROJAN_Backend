# ROJAN Backend Architecture Implementation Report v1.0

Role: Backend Engineer, working under approved ROJAN AI architecture decisions. Scope: verify the approved model against the current implementation, make only the specifically required change, prepare Web Dashboard integration. No architecture redesign, no new patterns introduced without approval.

---

## 1. Ownership Model Confirmation

**Approved model:**
```
User -> Salon.ownerId -> Dashboard Access
Salon Owner = authenticated User where User.id == Salon.ownerId
```

**Verified against source — confirmed compliant:**
- `domain/salon/Salon.kt`: `ownerId: UserId` is the sole owner reference on the `Salon` aggregate, set once at `Salon.create()`, never reassigned (no ownership-transfer method exists).
- `domain/salon/SalonRepository.kt`: `findByOwnerId(ownerId: UserId): List<Salon>` is the only lookup mechanism by ownership — no separate "membership"/"staff" table.
- `application/dashboard/GetDashboardInsightsUseCase.kt`: resolves the dashboard's salon via `salonRepository.findByOwnerId(command.callerId)` and nothing else. This is the exact rule as specified.
- **No `OWNER` role exists** in `UserRole` (`domain/user/User.kt`: `CUSTOMER`, `MANAGER`, `SPECIALIST` only) — confirmed no `OWNER` value was ever added, and none was added in this pass. Codebase-wide search for `@PreAuthorize`/`hasRole`/`@Secured`/`@RolesAllowed` returns zero results — ownership is never enforced via Spring Security role machinery, only via direct ID comparison in application-layer code, exactly as the approved model implies (dashboard access is a *relationship* check, not a *role* check).

**No code change required or made for this section.** Rule documented here as instructed.

---

## 2. JWT Claims Standardization

**Approved shape:** `{ userId, roles }`. `salonId` must never appear.

**Verified against `infrastructure/security/JwtTokenProvider.kt`:**

| Claim | Approved name | Actual claim | Compliant? |
|---|---|---|---|
| User identifier | `userId` | `sub` (JWT-standard "subject" claim, holds `user.id`) | **Functionally yes.** The value is the user's ID; it's carried under the JWT-standard `sub` key rather than a custom `userId` key. Renaming `sub` → a custom `userId` claim was **not done** in this pass — it would be a breaking change to every token consumer (`JwtAuthenticationFilter`, `RefreshTokenUseCase`) with no functional benefit, and this ticket's own instruction is "do not introduce new patterns without approval." Flagged as a naming discrepancy only. |
| Role(s) | `roles` (plural, implying array) | `role` (singular string — `user.role.name`) | **Functionally yes, structurally singular.** Each `User` has exactly one `UserRole` today (`CUSTOMER`/`MANAGER`/`SPECIALIST`) — there is no multi-role concept anywhere in the domain model, so a plural array would currently always contain exactly one element. Not changed to an array in this pass for the same reason as above: no domain concept of multiple roles exists to populate it with, and changing the wire shape (`role` string → `roles` array) is a breaking change to any existing token consumer. |
| Tenant/salon | must be **absent** | absent | **Confirmed compliant.** No `salonId`, `tenantId`, or any salon-related claim exists anywhere in `JwtTokenProvider`. Tenant context is resolved dynamically per the approved flow: `JWT → userId (sub claim) → SalonRepository.findByOwnerId → Salon`, exactly as specified, implemented in `GetDashboardInsightsUseCase` (§1, §3). |

Full current claim set (both access and refresh tokens): `sub`, `email`, `role`, `type` (`ACCESS`/`REFRESH`), `iss`, `iat`, `exp`, `jti`.

**No code change made.** The two naming discrepancies (`sub` vs. `userId`, `role` vs. `roles`) are cosmetic/structural, not violations of the approved principle (no salonId, tenant resolved dynamically) — renaming either is a breaking wire-format change affecting every existing client and is listed under Architecture Decisions Required rather than silently applied.

---

## 3. Dashboard Authorization Review

**Endpoint:** `GET /api/v1/dashboard/insights`. **Rule:** `callerId == salon.ownerId`.

**Verified implementation** (`GetDashboardInsightsUseCase.execute`):
```kotlin
val ownedSalons = salonRepository.findByOwnerId(command.callerId)
val salon = when (ownedSalons.size) {
    0 -> throw SalonNotFoundException(command.callerId.value.toString())
    1 -> ownedSalons.single()
    else -> throw AmbiguousSalonContextException(command.callerId.value.toString())
}
```
This *is* the `callerId == salon.ownerId` check — by construction, `findByOwnerId` can only return salons the caller owns, so there is no separate "found a salon, now verify ownership" step needed (unlike endpoints that take an explicit `salonId` path variable and must check ownership after the fact).

**Confirmed test coverage** (`application/test/dashboard/GetDashboardInsightsUseCaseTest.kt`, `bootstrap/test/DashboardInsightsFlowIntegrationTest.kt`):

| Case | Expected | Verified by |
|---|---|---|
| No/invalid bearer token | `401`, empty body | `DashboardInsightsFlowIntegrationTest.insights requires a bearer token` |
| Authenticated, owns 0 salons | `404` | `... an owner with no salon gets 404` (integration) + unit test `throws when caller owns no salon` |
| Authenticated, owns 1 salon | `200`, computed insights | `... an owner with exactly one salon gets a complete, zeroed empty-state response` (integration) + unit tests for populated data |
| Authenticated, owns 2+ salons | `409` | `... an owner with two salons gets 409` (integration) + unit test `throws when caller owns more than one salon` |

All four cases re-ran clean in this pass's `./gradlew clean build` (see §Tests below). **No code change was required** — this was fully implemented in a prior sprint; this section is verification only.

---

## 4. Web Authentication Bridge Preparation

**Target architecture (not yet implemented):**
```
Login -> JWT -> HttpOnly Secure Cookie -> Next.js Session -> Backend API
```

**Current login implementation** (`AuthController.login` → `AuthenticateUserUseCase`): returns `accessToken` + `refreshToken` as **JSON body fields**, not cookies. No `Set-Cookie` header is ever written anywhere in the codebase (confirmed — no `ResponseCookie`/`HttpServletResponse.addCookie` usage found). The client (today: presumably a mobile/direct-API client) is responsible for storing both tokens and attaching the access token as an `Authorization: Bearer` header on every subsequent request. This is a complete, working, stateless bearer-token flow — it is simply not cookie-based today.

**Backend requirements to prepare for the target architecture** (analysis only — **no backend code was changed** for this section, per the ticket's "prepare requirements" framing and "do not implement frontend changes" instruction, which this treats as "do not implement this bridge at all yet," since it's a two-sided contract that shouldn't be half-built):

| Requirement | What it means for this backend | Current state |
|---|---|---|
| **Secure** | Cookie must carry the `Secure` attribute (HTTPS-only transmission) | N/A — no cookie exists yet. Nginx already terminates TLS in prod (`DEPLOYMENT.md` §2), so the precondition (HTTPS reaching the app, or at least the edge) is already met. |
| **HttpOnly** | Cookie must be inaccessible to JavaScript (`document.cookie`), mitigating XSS token theft | N/A — no cookie exists yet. This is the main *security upgrade* a cookie bridge would deliver over today's model, where an access token stored in JS-accessible storage (localStorage/memory, Web's choice, outside this backend's control) is readable by any injected script. |
| **SameSite** | Must be set (`Lax` or `Strict`) to bound CSRF exposure | N/A — no cookie exists yet. Value choice (`Lax` vs `Strict`) depends on whether Web ever needs the cookie sent on a cross-site top-level navigation (e.g., an email link landing on an authenticated page) — an explicit product decision, not inferable from the current codebase. |
| **HTTPS in production** | Already true — see Secure row | Already satisfied by existing Nginx TLS termination (`docker/nginx/`), independent of this bridge. |
| **CSRF protection** | Required the moment a cookie carries auth (§Security Audit §3) | **Not present.** `.csrf { it.disable() }` is correct for today's header-only model and becomes **incorrect** the moment a cookie is introduced. This is the single largest actual code change the real implementation of this bridge will require — re-enabling and correctly scoping CSRF protection in `SecurityConfig`. |
| **Refresh flow under cookies** | Needs a decision: does the refresh token also move into a (separate, more restricted) cookie, or stay in the JSON body for Next.js's server-side session layer to manage? | Undecided — not in scope for this preparation pass. |
| **Cookie-issuing endpoint** | `AuthController.login`/`.refresh` would need to additionally (or instead) write `Set-Cookie` response headers | Not implemented — no `Set-Cookie` anywhere today. |

**This is intentionally analysis-only.** Implementing the bridge is an architectural change (new auth transport, new CSRF posture, new SecurityConfig behavior) beyond "prepare requirements," and beyond what "do not introduce new patterns without approval" permits without an explicit go-ahead on the specifics (cookie name, domain/path scoping, `SameSite` value, whether the refresh token moves too). Listed under Architecture Decisions Required.

---

## 5. API Error Contract — Code Change Made

**Before:** `ApiError` had no machine-readable error identifier — only free-text `message`, which is not a stable contract for client branching logic.

**Change:** added a non-nullable `errorCode: String` field to `ApiError` (`api/common/GlobalExceptionHandler.kt`), populated for every existing `@ExceptionHandler` via a new private `errorCodeFor(ex: Throwable): String` mapping function. This is purely additive to the response body — no status code, no routing, no exception type changed. Existing clients that don't read the new field are unaffected.

The three codes this ticket specifies are implemented exactly as named:

| Status | `errorCode` | Exception |
|---|---|---|
| 404 | `SALON_NOT_FOUND` | `SalonNotFoundException` |
| 409 | `SALON_CONTEXT_REQUIRED` | `AmbiguousSalonContextException` |
| 401 | `AUTH_UNAUTHORIZED` | **Not applied — see blocker below** |

All other existing exceptions received a corresponding code too (`EMAIL_ALREADY_REGISTERED`, `INVALID_CREDENTIALS`, `BOOKING_NOT_FOUND`, `VALIDATION_FAILED`, `INTERNAL_ERROR`, etc. — full mapping in `GlobalExceptionHandler.errorCodeFor`), so that the field is consistently present rather than sometimes-populated, which would itself be an inconsistency.

### Blocker: `AUTH_UNAUTHORIZED` cannot be delivered today

A 401 caused by a missing/invalid/expired JWT is produced by `SecurityConfig`'s `HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)`, at the Spring Security filter level, **before the request reaches `GlobalExceptionHandler`**. That response has an **empty body** (`Content-Length: 0`) — there is no `ApiError` to attach an `errorCode` to. Making 401 also carry `{"errorCode": "AUTH_UNAUTHORIZED", ...}` requires writing a JSON body from `SecurityConfig`'s entry point, i.e. **modifying `SecurityConfig`**. This session's standing instruction is not to touch `SecurityConfig` "unless a real security issue exists" — an inconsistent error body is a documentation/DX gap, not a security issue, so this was **not done**. Listed as an open item requiring explicit sign-off, not silently applied.

---

## 6. Dashboard API Documentation

See **`ROJAN_Dashboard_API_Contract_v1.md`** (separate file, this pass) — full endpoint, auth, tenant-resolution, response, and error documentation including the new `errorCode` field.

---

## 7. Security Review

See **`ROJAN_Security_Audit_v1.md`** (separate file, this pass) — JWT validation, CORS, CSRF, rate limiting, secret management, public endpoint surface. No major security changes were made, per instruction; findings are documented, not fixed.

---

## 8. AI Engine Protection — Compatibility Verified, No Changes Made

Confirmed via `git status` before and after this pass: none of `application/dashboard/RecommendationEngine.kt` (the `InsightEngine` interface + `Recommendation`/`SalonInsightMetrics` types), `application/dashboard/RuleBasedRecommendationEngine.kt`, or the `Recommendation`/`RecommendationResponse` DTOs were opened for edit in this pass.

**Compatibility with the Dashboard API check:** `DashboardController` still maps `DashboardInsights.recommendations` (a `List<Recommendation>`) into `DashboardInsightsResponse.recommendations` (`List<RecommendationResponse>`) unchanged. The only file in the error-handling path that changed (`GlobalExceptionHandler.kt`) is unrelated to the success-path recommendation payload — a `200` response's `recommendations` array shape is identical to before this pass. `./gradlew clean build` (below) re-ran `RuleBasedRecommendationEngineTest` (14 tests) and `GetDashboardInsightsUseCaseTest` (6 tests, including the ones asserting `recommendations` content) with no changes needed and no failures.

---

## Files Changed

| File | Change |
|---|---|
| `api/src/main/kotlin/ai/rojan/backend/api/common/GlobalExceptionHandler.kt` | Added `ApiError.errorCode: String`; added `errorCodeFor()` mapping; every `@ExceptionHandler` now supplies a code |

**No other source file was modified in this pass.** (`git status` before this task already showed several files modified/untracked from prior sprints in this same session — those are unrelated to this ticket and were left as-is.)

## Files Created

| File | Purpose |
|---|---|
| `ROJAN_Backend_Architecture_Implementation_Report.md` | This document |
| `ROJAN_Dashboard_API_Contract_v1.md` | §6 deliverable |
| `ROJAN_Security_Audit_v1.md` | §7 deliverable |

## Tests Executed

```
./gradlew clean build
```
Result: **BUILD SUCCESSFUL**, all modules including `bootstrap`'s Postgres-backed integration suite (which exercises the real HTTP layer, including `DashboardInsightsFlowIntegrationTest`'s 401/404/200/409 cases against the endpoint this ticket audits, and `ApiHardeningIntegrationTest`, which deserializes live `ApiError` responses — confirming the new `errorCode` field doesn't break existing error-body consumers).

```
./gradlew bootJar
```
Result: **BUILD SUCCESSFUL** — `bootstrap/build/libs/bootstrap-0.1.0-SNAPSHOT.jar` regenerated with the `errorCode` change included.

*(Neither command was followed by a deployment. Per standing project context, shipping a rebuilt jar to the production server requires a manual `scp` + `docker compose build/up` cycle this session cannot perform — no SSH credentials are available in this environment.)*

## Remaining Blockers

1. **`AUTH_UNAUTHORIZED` cannot be added to the 401 response body without modifying `SecurityConfig`** (§5) — needs explicit approval given the standing "don't touch SecurityConfig" instruction.
2. **CORS is unconfigured** — a real blocker only if ROJAN Web is ever hosted on a different origin than this API; not an issue under the same-origin-via-Nginx deployment `DEPLOYMENT.md` describes. Needs a decision either way before Web integration assumes cross-origin calls will work.
3. **No jar has been deployed to production** in this session — this pass only builds and verifies locally.

## Recommended Next Steps

1. **Decide on the 401 body format** (§5 blocker) — either accept the current empty-body 401 as final, or approve the specific `SecurityConfig` change needed to emit `{"errorCode": "AUTH_UNAUTHORIZED", ...}`.
2. **Decide on CORS** before Web integration testing begins, especially if Web's dev environment runs on a different port/origin than the API (very likely during local development, even if prod is same-origin via Nginx).
3. **Multi-salon dashboard UX** (already flagged in the prior architecture audit) — `409 SALON_CONTEXT_REQUIRED` currently has no resolution path for a genuine multi-salon owner; Web needs to know whether this is acceptable indefinitely or needs a salon-picker + explicit-selection endpoint.
4. **When ready to implement the HttpOnly cookie bridge** (§4), scope it as its own ticket — it touches `SecurityConfig` (CSRF re-enablement), `AuthController` (cookie-writing), and needs explicit answers on cookie name/domain/path/`SameSite` value and whether the refresh token moves into a cookie too.
5. **Deploy this pass's jar** (`bootstrap-0.1.0-SNAPSHOT.jar`, contains the `errorCode` field) to the server via the established `scp` + `docker compose build/up` flow, then verify a live `404`/`409` response on `/api/v1/dashboard/insights` includes the new field.
