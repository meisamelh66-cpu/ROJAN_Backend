# ROJAN Team 1 — Integration Verification Report v1

**Scope:** Verification only, per "ROJAN Backend Team 1 — Verification Review Order" (P0). Validates current implementation status of the Customer Management API and Dashboard Insights API against previously reported Mobile Manager (Owner) App integration blockers. No code, schema, or contract changes were made in the course of this review.

**Repositories at time of review:**
- `ROJAN_Backend` @ `6943986` ("docs: add ROJAN Milestone Backup v3.0 report"), branch `master`, clean.
- `ROJAN_Desktop` (Owner App) @ `8526235` ("ROJAN milestone: Service Specialist and CRM integrations completed"), branch `main`, clean.

---

## Executive Summary

The blockers previously reported against Customer Management and Dashboard Insights are **outdated**. Both APIs are fully implemented on the backend, both are consumed by real (not fake/stub) repository implementations in the Owner App, and both are wired into production DI. Backend unit tests (48/48) and Owner App infrastructure tests (23/23) covering these two areas pass.

One limitation flagged in prior backend reports is still real and unresolved by design: **walk-in (unlinked) customers have no booking history**, because it depends on a future Booking-domain enhancement (owner-initiated booking creation on behalf of a walk-in). This is correctly implemented as an empty/zero result on both ends, not an error — see §1.4. Per this order's scope, that enhancement is **not** implemented here.

| Area | Verdict |
|---|---|
| Customer API | **PASS** |
| Dashboard API | **PASS** |
| Security | **PASS** |
| API Contract | **PASS** |
| Mobile Manager (Owner) App Readiness | **READY** |

---

## 1. Customer Management API Validation

Source: `api/src/main/kotlin/ai/rojan/backend/api/customer/CustomerController.kt` + `CustomerDtos.kt`. All routes mounted under `/api/v1/salons/{salonId}/customers`.

### 1.1 Customer list

| | |
|---|---|
| Endpoint | `GET /api/v1/salons/{salonId}/customers` |
| Method | GET, paginated (`page`, `size`, optional `status`/`tag`/`search`/`sortDirection`) |
| Authentication | Bearer JWT, enforced by `SecurityConfig` (`anyRequest().authenticated()`; only `/api/v1/auth/**` and `/api/v1/public/**` are public) |
| Authorization | Owner-only — controller resolves the salon, throws `SalonAccessDeniedException` (403) if `salon.ownerId != callerId` |
| Response Contract | `PagedResponse<CustomerResponse>` (`content`, `page`, `size`, `totalElements`, `totalPages`) |
| Current Consumer Status | **Integrated** — `BackendCustomerRepository.FetchAllCustomersAsync` (Owner App) pages through this endpoint and is registered as the live `ICustomerRepository` in `ServiceCollectionExtensions.cs:87` |

### 1.2 Customer profile

| | |
|---|---|
| Endpoint | `GET /api/v1/salons/{salonId}/customers/{customerId}` |
| Method | GET |
| Authentication | Bearer JWT |
| Authorization | Owner-only, checked explicitly in the controller (`requireOwner`) — this is the one pure-read path with no underlying use case, so the check is intentionally inline (see the controller's own doc comment) |
| Response Contract | `CustomerResponse` (id, salonId, userId?, fullName, phoneNumber?, email?, company?, status, lifetimeValue, tags[], active, createdAt, updatedAt) |
| Current Consumer Status | **Integrated** — `BackendCustomerRepository.GetCustomerByIdAsync`, maps 404 to `null` as the Owner App domain expects |

### 1.3 Customer timeline

| | |
|---|---|
| Endpoint | `GET /api/v1/salons/{salonId}/customers/{customerId}/timeline` |
| Method | GET, paginated |
| Authentication | Bearer JWT |
| Authorization | Owner-only, enforced inside `GetCustomerTimelineUseCase` |
| Response Contract | `PagedResponse<CustomerTimelineEntryResponse>` — merged feed of `STATUS_CHANGED \| TAG_ADDED \| TAG_REMOVED \| NOTE \| BOOKING_CREATED \| BOOKING_CONFIRMED \| BOOKING_COMPLETED \| BOOKING_CANCELLED` |
| Current Consumer Status | **Integrated** — `BackendCustomerRepository.GetActivityAsync` pages through it and synthesizes a display-only local id per entry (backend timeline entries have none, by design) |

### 1.4 Customer booking history

| | |
|---|---|
| Endpoint | `GET /api/v1/salons/{salonId}/customers/{customerId}/bookings` |
| Method | GET, paginated, optional `status`/`sortDirection` |
| Authentication | Bearer JWT |
| Authorization | Owner-only, enforced inside `GetCustomerBookingsUseCase` |
| Response Contract | `PagedResponse<BookingResponse>` |
| Current Consumer Status | **Integrated** on the read path; the Owner App's own `CustomerProfileQueryService` correctly expects an honestly-empty result for an unlinked customer |

**Confirmed remaining limitation:** `GetCustomerBookingsUseCase` returns an empty page whenever `customer.userId == null` (a walk-in/manually-created CRM record with no linked account) — this is correct, intended behavior, not a bug. Backing a walk-in customer with real booking history requires the Booking domain to support owner-initiated booking creation on behalf of an unlinked customer, which does not exist yet. This was already documented in `ROJAN_Customer_CRM_Implementation_Report_v1.md` §6.1 and remains accurate today. **Per this order's scope, this enhancement is not implemented as part of this verification.**

---

## 2. Dashboard Insights API Validation

`GET /api/v1/dashboard/insights` — `DashboardController.kt` / `GetDashboardInsightsUseCase.kt`.

| Check | Result |
|---|---|
| Endpoint availability | Present, implemented, no `salonId` path/query param needed — resolves the caller's own salon from the JWT |
| Authentication requirement | Bearer JWT required (`SecurityConfig`); 401 via the security-level entry point if missing/invalid |
| Owner authorization | Enforced by construction: `salonRepository.findByOwnerId(callerId)` — 404 `SalonNotFoundException` if the caller owns no salon, 409 `AmbiguousSalonContextException` if the caller owns more than one (both documented in the controller's `@ApiResponses`) |
| Response contract compatibility | `DashboardInsightsResponse` (revenue{today,month,growthRate}, bookings{total,completed,cancelled}, customers{newCustomers,returningCustomers}, services[], recommendations[]) — matches `Rojan.Desktop.Application.Api.Contracts.DashboardInsightsResponse.cs` field-for-field |
| Mobile Manager (Owner) App consumption readiness | **Ready and already wired** — `BackendDashboardRepository` calls this exact endpoint and is registered as the live `IDashboardRepository` (`ServiceCollectionExtensions.cs:74`), replacing `FakeDashboardRepository` |

**Verdict: PASS**

---

## 3. API Contract Review

- **DTOs:** `CustomerResponse`, `CustomerNoteResponse`, `CustomerTagResponse`, `CustomerTimelineEntryResponse`, `DashboardInsightsResponse` and their nested shapes were compared field-by-field against the Owner App's `Rojan.Desktop.Application.Api.Contracts` equivalents. No mismatches found.
- **Pagination envelope:** `PagedResponse<T>` (`content`, `page`, `size`, `totalElements`, `totalPages`) is identical on both sides (`api/common/PagedResponse.kt` vs. `Api/Contracts/PagedResponse.cs`), confirmed by the Owner App's own doc comment cross-referencing the backend file.
- **Version compatibility:** No `/api/v1` contract changes were made or proposed. `ApiError`'s `traceId`/`errorCode` fields are additive-only, explicitly kept backward-compatible per `GlobalExceptionHandler`'s own doc comment (existing clients may ignore them).
- **Breaking-change risk:** None identified. This review made no contract, schema, or DTO changes, per the order's constraints.

**Verdict: PASS**

---

## 4. Security Review

- **JWT validation:** `JwtAuthenticationFilter` validates the bearer token via `TokenProviderPort.validateAndExtractSubject`, rejects non-`ACCESS` token types (e.g. a refresh token can't double as an API credential), and treats malformed/expired tokens or a deleted-account subject as anonymous rather than throwing raw exceptions past the filter.
- **Owner authorization:** Every Customer/Dashboard read and write path re-derives the salon from the resource being accessed and compares `salon.ownerId` against the resolved caller id, throwing `SalonAccessDeniedException`/`CustomerAccessDeniedException` (403) on mismatch. `CurrentUserResolver` additionally re-verifies the JWT subject against `UserRepository` on every request, so a token for a since-deleted account is rejected rather than trusted from stale claims.
- **Salon tenant isolation:** Confirmed at the use-case layer (not just the controller) for list, get, timeline, and bookings — e.g. `GetCustomerBookingsUseCase` and `GetCustomerTimelineUseCase` both independently re-check `salon.ownerId != callerId` rather than relying on the controller having already checked. `findCustomerOrThrow` additionally scopes lookups to the path's `salonId`, so a valid `customerId` from a different salon 404s rather than leaking cross-tenant.
- **Role permissions:** Authorization here is ownership-based (salon-owner-only), not a generic role check — there is no role that can read another owner's customers or dashboard. `RojanUserDetailsService` still stamps a `ROLE_*` authority from `user.role` for use elsewhere in the API, but Customer/Dashboard access control is driven entirely by salon ownership, which is the stricter and correct model for CRM/financial data.

**Verdict: PASS**

---

## 5. Test Evidence (executed as part of this verification, no code touched)

| Suite | Result |
|---|---|
| `ROJAN_Backend` — `application:test`, filtered to `ai.rojan.backend.application.customer.*` and `ai.rojan.backend.application.dashboard.*` | **48/48 passed**, 0 failures, 0 errors |
| `ROJAN_Desktop` — `Rojan.Desktop.Infrastructure.Tests`, filtered to `BackendCustomerRepositoryTests` and `BackendDashboardRepositoryTests` | **23/23 passed**, 0 failed |

---

## Remaining Gaps

- Walk-in (unlinked) customers have no real booking history — gated on a future Booking-domain enhancement (owner-initiated booking creation for an unlinked customer). Not in scope for this order.
- `CalculateCustomerLifetimeValueUseCase` is an N+1 query per customer in the list endpoint (one `BookingRepository` call + up to N `ServiceRepository.findById` calls per row) — a known, accepted Phase 1 simplification, worth monitoring as salon customer lists grow.
- No dedicated controller-level (web-layer) test exists for `CustomerController`/`DashboardController` — authorization and mapping logic are covered at the use-case layer instead, which is where the actual checks live; this is a coverage-shape observation, not a defect.
- Identity reconciliation (linking a manually-created walk-in `Customer` to a same-phone `User` created later via Mobile OTP) is still unbuilt — `linkToUser()` exists and is unit-tested on the domain model, but no endpoint calls it yet.

## Recommendations

- No rebuild or refactor of Customer Management or Dashboard Insights is warranted — both are correctly implemented, contract-aligned, secured, and already integrated by the Owner App.
- Treat the walk-in booking-history gap as a distinct, future Booking-domain ticket rather than reopening Customer CRM scope, consistent with how it was already tracked in `ROJAN_Customer_CRM_Implementation_Report_v1.md`.
- If Phase 3 (Calendar/Availability Integration) work touches the Booking domain, consider whether owner-initiated booking creation for walk-ins can be scoped in at the same time, since it would directly close this gap — but only as a deliberate, separately-scoped decision, not as a side effect.
