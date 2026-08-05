# ROJAN Customer Experience Phase 1 — Readiness Audit v1

**Priority:** P0
**Mode:** Audit only. No code, contract, database, or API changes made in this pass.
**Repositories:** `ROJAN_Backend` @ `6943986` (audited directly, controller-by-controller), `ROJAN_Desktop` (confirmed to contribute no customer-facing code - see §0).
**Goal:** Evaluate readiness for (1) a Customer Web Booking MVP and (2) Customer Mobile App connectivity.

---

## 0. Scope Note on `ROJAN_Desktop`

`ROJAN_Desktop` is the Owner/Reception WPF desktop app - confirmed throughout this session's extensive work in that repo (Auth login/OTP-login is for the *owner*, Dashboard, Customer CRM, Service/Specialist catalog authoring, Calendar/Availability, reception-initiated Booking, Salon onboarding). It has no anonymous or customer-self-service entry point of any kind, and is not itself a customer channel. It is relevant to this audit only as **evidence that the backend contracts below are already real and consumed** (not speculative) - every endpoint in §1 that the Owner App also uses has already been integration-verified against a live consumer in prior work this session. The customer-facing gap this audit investigates is entirely about *new* channels (web, mobile) that don't exist yet, not about `ROJAN_Desktop` itself.

---

## 1. Backend Customer Experience API Audit

### Authentication — OTP request / verify

| | |
|---|---|
| Controller | `AuthController` |
| File path | `api/src/main/kotlin/ai/rojan/backend/api/auth/AuthController.kt` |
| Endpoint | `POST /api/v1/auth/otp/request` (line 119), `POST /api/v1/auth/otp/resend` (line 139), `POST /api/v1/auth/otp/verify` (line 159) |
| HTTP Method | POST (all three) |
| DTO | `OtpRequestRequest`/`OtpResendRequest`/`OtpVerifyRequest` → `OtpIssuedResponse` / `AuthResponse` — `api/src/main/kotlin/ai/rojan/backend/api/auth/OtpDtos.kt`, `AuthDtos.kt` |
| Authentication | Public (`/api/v1/auth/**` is in `SecurityConfig.PUBLIC_ENDPOINTS`) |
| Tenant rules | None - identity-only, no salon scoping. First-time verification of a phone number auto-registers the account (`VerifyOtpUseCase`) - **this is the customer registration path**, there is no separate "sign up" step for phone-only accounts. |

### Salon discovery — Search

| | |
|---|---|
| Controller | `SalonController` |
| File path | `api/src/main/kotlin/ai/rojan/backend/api/salon/SalonController.kt` |
| Endpoint | `GET /api/v1/salons` (line 89) |
| HTTP Method | GET |
| DTO | `PagedResponse<SalonResponse>` — response DTO in same file |
| Authentication | **Authenticated (not public)** - no `@AuthenticationPrincipal` param needed by the method itself, but not listed in `SecurityConfig.PUBLIC_ENDPOINTS`, so `anyRequest().authenticated()` applies. A valid bearer token is required just to browse. |
| Tenant rules | None (cross-tenant by design - this *is* the discovery endpoint). Filtered server-side to `active` salons only, paginated, optional `name` filter, sortable. |

### Salon profile

| | |
|---|---|
| Controller | `SalonController` |
| File path | `api/src/main/kotlin/ai/rojan/backend/api/salon/SalonController.kt` |
| Endpoint | `GET /api/v1/salons/{salonId}` (line 101) |
| HTTP Method | GET |
| DTO | `SalonResponse` (id, ownerId, name, description, phone, email, address, active, createdAt, updatedAt) |
| Authentication | Authenticated (same reasoning as Search above) |
| Tenant rules | None - single-resource lookup by id, no cross-tenant restriction (any authenticated user can view any salon's profile - correct for a public-facing profile). |

**Separately - a `/api/v1/public/**` path exists but is not a real salon-profile API.** `PublicWebsiteController` (`api/src/main/kotlin/ai/rojan/backend/api/website/PublicWebsiteController.kt`) maps `GET /api/v1/public/{tenantSlug}/website` and is publicly accessible (no auth), but returns **hardcoded mock data** (`"name" to "ROJAN AI"`, `"description" to "AI Beauty Platform"`, `"status" to "ACTIVE"`) regardless of the `tenantSlug` path variable - it does not query `SalonRepository` at all. This is a stub, not a functioning anonymous-browse capability. Flagged in detail in §2.

### Service catalog

| | |
|---|---|
| Controller | `ServiceCategoryController` / `ServiceController` |
| File path | `api/src/main/kotlin/ai/rojan/backend/api/salon/ServiceCategoryController.kt`, `ServiceController.kt` |
| Endpoint | `GET /api/v1/salons/{salonId}/categories` (list), `GET .../categories/{categoryId}` (get); `GET /api/v1/salons/{salonId}/categories/{categoryId}/services` (list), `GET .../services/{serviceId}` (get) |
| HTTP Method | GET |
| DTO | `ServiceCategoryResponse`, `ServiceResponse` (name, description, durationMinutes, price, active, ...) |
| Authentication | Authenticated (same pattern as Salon list/get - no principal needed, but gated by the global `authenticated()` rule) |
| Tenant rules | Path-scoped to `salonId`/`categoryId` via `.takeIf { it.salonId == ... }` checks - correct. **No `active`-only filter on the list endpoints** - a deactivated category or discontinued service is returned to any authenticated caller, customer or not. Not a security gap (booking creation independently re-validates `active` - see below), but a catalog-hygiene gap for a customer-facing browse UI. |

### Specialist

| | |
|---|---|
| Controller | `SpecialistController` |
| File path | `api/src/main/kotlin/ai/rojan/backend/api/salon/SpecialistController.kt` |
| Endpoint | `GET /api/v1/salons/{salonId}/specialists` (list), `GET .../specialists/{specialistId}` (get) |
| HTTP Method | GET |
| DTO | `SpecialistResponse` (displayName, bio, photoUrl, active, ...) |
| Authentication | Authenticated (same pattern) |
| Tenant rules | Path-scoped to `salonId`. Same "no `active`-only filter" gap as the service catalog. |

### Availability

| | |
|---|---|
| Controller | `AvailabilityController` |
| File path | `api/src/main/kotlin/ai/rojan/backend/api/booking/AvailabilityController.kt` |
| Endpoint | `GET /api/v1/salons/{salonId}/specialists/{specialistId}/available-slots` |
| HTTP Method | GET |
| DTO | Query params `serviceId` (required), `date` (required), `slotIntervalMinutes` (default 15) → `List<TimeSlotResponse>` |
| Authentication | Authenticated (any authenticated user - already verified in this session's Calendar/Availability work) |
| Tenant rules | Path-scoped; the underlying `GetAvailableSlotsUseCase` re-validates the specialist/service exist, are active, and belong to the salon before computing anything - already audited in depth in this session's Calendar Phase 3 work, unchanged since. |

### Customer Booking — create

| | |
|---|---|
| Controller | `BookingController` |
| File path | `api/src/main/kotlin/ai/rojan/backend/api/booking/BookingController.kt` |
| Endpoint | `POST /api/v1/bookings` (line 75) |
| HTTP Method | POST |
| DTO | `CreateBookingRequest` (salonId, serviceId, specialistId, startTime, notes) → `BookingResponse` — `BookingDtos.kt` |
| Authentication | Authenticated. Customer identity is **always** resolved from the caller's own bearer token (`currentUserResolver.resolve(principal)`) - there is no `customerId` field on the request body, by design; this is the self-service path, distinct from the owner-initiated `SalonBookingController.createForCustomer` audited in a prior session. |
| Tenant rules | `CreateBookingUseCase` (`application/src/main/kotlin/ai/rojan/backend/application/booking/BookingUseCases.kt`) validates: salon exists and is `active`; service exists, belongs to that salon, and is `active`; specialist exists, belongs to that salon, and is `active` - throws 404 otherwise. Double-booking prevented atomically at `bookingRepository.reserve()`. Supports an optional `Idempotency-Key` header (replay-safe create). |

### Booking history

| | |
|---|---|
| Controller | `BookingController` |
| File path | `api/src/main/kotlin/ai/rojan/backend/api/booking/BookingController.kt` |
| Endpoint | `GET /api/v1/bookings/mine` (line 129) |
| HTTP Method | GET |
| DTO | `PagedResponse<BookingResponse>`, optional `status` filter, sortable |
| Authentication | Authenticated - `customerId` is always the caller's own id, never a path/query parameter. |
| Tenant rules | Not salon-scoped - correctly returns the customer's bookings across every salon they've ever booked with, since a customer is not tenant-bound the way a salon's data is. |

**Also present, not explicitly requested but directly relevant to a booking MVP:** `GET /api/v1/bookings/{bookingId}` (customer-or-owner only, line 155), `PATCH /{bookingId}/cancel` (customer-or-owner, line 180), `PUT /{bookingId}/reschedule` (customer-or-owner, line 203) - all in the same controller, all correctly authorized via `requireCustomerOrOwner` (`booking.customerId == callerId` OR `salon.ownerId == callerId`).

---

## 2. Customer Web Booking Readiness

**Flow audited:** Customer → Select Salon → View Services → Select Specialist → Select Availability → Create Booking, entered with **no pre-existing Owner account** (a brand-new customer).

### Missing APIs

**None, for the core flow once a customer holds a valid bearer token.** Every step - salon search, salon profile, service catalog, specialist list, availability, create booking - has a real, working, tenant-correct backend endpoint, confirmed by direct inspection in §1, not assumed.

**The actual gap is earlier: anonymous/pre-authentication browsing does not work.** `/api/v1/salons` (search) and `/api/v1/salons/{salonId}` (profile) both require a valid bearer token - there is no way for a not-yet-registered visitor to browse salons before completing OTP verification. The one endpoint that *is* publicly reachable without auth, `PublicWebsiteController`'s `/api/v1/public/{tenantSlug}/website`, returns hardcoded mock data unconnected to `SalonRepository` - it cannot answer "what does this salon actually offer" today. **This is a product decision as much as an engineering gap:** if the intended MVP flow is "log in via OTP first, then browse and book" (a very fast, low-friction step given OTP is already the whole auth model), nothing is missing. If the intended MVP flow is "browse anonymously, then log in only at the moment of booking" (the more common consumer-booking-site pattern), the public discovery/profile/catalog layer needs to be built - it does not exist today beyond the non-functional stub.

### Missing contracts

- No aggregated "salon detail" response combining profile + categories + services + specialists in one call - a web client today needs `GET /salons/{id}` + `GET /categories` + N × `GET /categories/{id}/services` + `GET /specialists` (N+1-shaped). Functionally complete, but a real client-perf/complexity cost worth deciding on before or shortly after MVP.
- No `active`-only server-side filter on category/service/specialist list endpoints (see §1) - a customer-facing browse screen would need to filter client-side, or the backend needs a query param added.

### Missing security rules

- **CORS is still unconfigured** (confirmed unchanged from the prior Launch Readiness Audit and explicitly out of scope for the Phase 1.1 work completed since) - directly relevant here, not hypothetical: a Customer Web app is exactly the browser-origin caller CORS exists to gate. If the web app is hosted on a different origin than the API (the nginx config already reverse-proxies the website at a separate domain from the API's own), every browser-side call will fail until a `CorsConfigurationSource` is added.
- No rate limiting on `POST /api/v1/bookings` itself (Phase 1.1 added rate limiting to `/auth/login`, `/auth/register`, `/auth/refresh` only, per that phase's explicit scope) - double-booking is prevented atomically regardless, so this is an abuse/spam concern, not a data-integrity one.
- No role check restricts who may create a booking as "the customer" - confirmed consistent with the rest of this codebase's existing design (`role` is not an authorization gate anywhere in this backend today, a fact already documented in prior auth work this session), not a new or web-specific gap.

---

## 3. Customer Mobile App Readiness

| Capability | Status | Evidence |
|---|---|---|
| Customer authentication | **Ready** | OTP request/verify (§1) is the same "Mobile Number → OTP → JWT" flow already documented as the approved mobile-first design - nothing owner-specific about it; a customer mobile app can use it unmodified. |
| Customer profile | **Partial** | `GET /api/v1/users/me` exists (`UserController`, `api/src/main/kotlin/ai/rojan/backend/api/user/UserController.kt`) - read-only. **No `PUT`/`PATCH` endpoint exists to update a customer's own name/email/phone** - confirmed by inspecting the full `UserController`, which has exactly one method. |
| Booking history | **Ready** | `GET /api/v1/bookings/mine` (§1) - paginated, status-filterable, already complete. |
| Notifications | **Not present** | No notification domain, no notification endpoints, confirmed by a repository-wide search across `api`/`application`/`domain`/`infrastructure` for notification-related terms - zero matches. A booking confirmation today only reaches a customer if they poll `GET /bookings/mine` themselves. |
| Push notification readiness | **Not present** | No FCM/APNs integration, no device-token registration endpoint, no push-sending infrastructure anywhere in the backend - confirmed by the same repository-wide search (zero matches for push/FCM/APNs/firebase). This is a full gap, not a partial one. |

---

## 4. Architecture Review

### Multi-tenant isolation

**Consistent and correctly enforced**, confirmed across every controller read in this audit: every salon-scoped resource (categories, services, specialists, bookings via availability) is looked up by its own id and then checked against the path's `salonId` (`.takeIf { it.salonId == SalonId(salonId) }` or equivalent), 404-ing rather than leaking cross-tenant data on a mismatch. This matches the same pattern already verified in depth for Customer CRM and Dashboard in a prior session's Team 1 verification.

### Customer identity model

**Two distinct identity concepts exist, connected only by an optional link - this is the single most important architectural fact for both customer-facing initiatives:**

1. **`User`** (`UserId`) - the real authentication identity. Created via `POST /auth/register` (email/password) or auto-created on first successful `POST /auth/otp/verify` (phone-only, no separate registration step). **This is what "customer" means for every self-service endpoint in §1** - `Booking.customerId` is a `UserId`, not a CRM record id.
2. **`Customer`** (`CustomerId`) - a salon-owned CRM record (name, phone, email, tags, notes, status), created by an *owner* through the Owner App's Customer CRM, optionally linked to a real `User` via a nullable `Customer.userId` field.

These are **not automatically reconciled**. A customer who self-registers via OTP and books directly through the future web/mobile app does not automatically get a CRM `Customer` record in any salon they've booked with, and a salon's manually-created CRM `Customer` record is not automatically linked to that same person's real account even if the phone numbers match. `linkToUser()` exists on the `Customer` domain model and is unit-tested, but nothing calls it yet - already documented as unbuilt in a prior session's Customer CRM work, confirmed still true.

**This does not block the Customer Web Booking MVP or Customer Mobile App connectivity** - self-service booking never touches the CRM `Customer` model at all. It does mean an owner's CRM will not automatically reflect a self-service customer as "their" customer without a future reconciliation feature - a real, known limitation for the *owner's* view of the data, not for the customer-facing flow itself.

### Booking ownership

**Consistent.** `Booking.customerId` is always a `UserId` across every code path checked (`CreateBookingUseCase`, `CancelBookingUseCase`, `RescheduleBookingUseCase`, `BookingController.get`) - authorization is uniformly `booking.customerId == callerId` OR `salon.ownerId == callerId` (`requireCustomerOrOwner`), with no third identity shape to reconcile.

### Walk-in limitation

**Confirmed still real, unchanged since the prior session's Team 1 verification.** A manually-created (walk-in) `Customer` CRM record with no linked `User` correctly shows empty booking history and zero lifetime value - by design, not a bug, pending the same `linkToUser()` reconciliation feature named above. Irrelevant to the customer-facing flow itself (a self-service customer is never a "walk-in" in this sense - they always have a real `User` the moment they complete OTP verification).

---

## 5. Final Classification

| Item | Classification |
|---|---|
| Customer OTP request/verify (registration + login) | 🟢 **READY** |
| Self-service booking create (`POST /bookings`) | 🟢 **READY** |
| Booking history, get, cancel, reschedule | 🟢 **READY** |
| Availability computation | 🟢 **READY** |
| Salon search/profile (authenticated) | 🟢 **READY** |
| Service/Specialist catalog reads (authenticated) | 🟢 **READY**, functionally - see catalog-hygiene note below |
| Multi-tenant isolation | 🟢 **READY** |
| Booking ownership model | 🟢 **READY** |
| CORS for a browser-hosted Customer Web app | 🔴 **BLOCKER** - already flagged pre-existing, still unresolved; will hard-fail every browser-origin call if the web app is cross-origin from the API |
| Anonymous/pre-auth salon discovery (`PublicWebsiteController`) | 🔴 **BLOCKER**, conditional on product intent - only a blocker if "browse before you log in" is required for MVP; the authenticated fallback already works today |
| `active`-only filtering on category/service/specialist list endpoints | 🟠 **NEEDED BEFORE MVP** - catalog-hygiene, not a security gap (booking creation independently re-validates `active`) |
| Customer profile update (`PUT`/`PATCH /users/me`) | 🟠 **NEEDED BEFORE MVP** - read exists, write doesn't |
| In-app/email/SMS booking-confirmation notifications | 🟠 **NEEDED BEFORE MVP** - reasonable minimum customer expectation for a booking product; the existing SMS provider integration (already wired for OTP) is the fastest path, not starting from zero |
| Aggregated salon-detail endpoint (reduce N+1 client calls) | 🟡 **POST LAUNCH** - functional today via multiple calls, a performance/DX improvement, not a blocker |
| Rate limiting on `POST /bookings` | 🟡 **POST LAUNCH** - abuse-prevention, not data-integrity (double-booking already atomic) |
| Push notification infrastructure (FCM/APNs) | 🟡 **POST LAUNCH** - a genuinely larger lift, reasonable to defer past MVP |
| Customer↔CRM identity reconciliation / walk-in linking | 🟡 **POST LAUNCH** - improves the *owner's* CRM completeness; does not block the customer-facing flow itself |

---

## Summary

The backend's *core* customer booking capability - discover a salon, see its catalog, check real availability, create a booking, see booking history - is **complete and already correctly tenant-isolated**, once a customer is authenticated. Nothing in the five-step flow required by this audit is missing at the endpoint level. The real blockers are narrower and specific: **CORS** (a hard technical blocker for any browser-hosted web client the moment it's cross-origin) and **anonymous discovery** (`PublicWebsiteController` is a non-functional stub, which only matters if pre-login browsing is a product requirement - a decision this audit surfaces rather than makes). Mobile connectivity is in a similar position: authentication and booking history are ready today, while profile editing and any form of notifications (in-app or push) do not exist yet and would need to be built.

**No code, contract, or implementation changes were made in this investigation. Awaiting approval before any follow-on work begins.**
