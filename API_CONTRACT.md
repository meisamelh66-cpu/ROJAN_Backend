# ROJAN Backend — API Contract

Base path: **`/api/v1`** for every business endpoint. All requests/responses
are JSON (`Content-Type: application/json`). This is the authoritative,
human-readable contract; the live, always-current machine-readable spec is
served at `/v3/api-docs` (Swagger UI at `/swagger-ui/index.html`), which
additionally carries field-level examples added in the API-hardening
milestone. (`API.md` is the older auth-only version of this document and
now just points here.)

## Contents

1. [Conventions](#conventions) — versioning, auth, errors, pagination, idempotency, authorization model
2. [Authentication](#authentication)
3. [Salon](#salon)
4. [Branch](#branch)
5. [Service Categories](#service-categories)
6. [Services](#services)
7. [Specialists](#specialists)
8. [Availability](#availability) — working hours, specialist schedule, computed slots
9. [Booking](#booking)

---

## Conventions

### Versioning

Every business endpoint lives under `/api/v1/`. A breaking change ships as
a new version prefix (`/api/v2/`) rather than mutating `/api/v1/` in place;
nothing in this milestone changed that.

### Authentication & authorization model

Bearer JWT (`Authorization: Bearer <accessToken>`), stateless. Three broad
authorization patterns recur across every resource below:

| Pattern | Meaning |
|---|---|
| **Public** | No token required. Only `/api/v1/auth/register`, `/login`, `/refresh`. |
| **Any authenticated user** | Any valid access token, regardless of role — used for browsing/reading (salons, services, specialists, availability). |
| **Owner only** | The caller must be the `ownerId` of the salon that (directly or transitively) owns the resource. Enforced by resolving the caller's `UserId` and comparing to `Salon.ownerId` — never by trusting a client-supplied id. |
| **Customer or owner** | Either the booking's `customerId` or the owning salon's `ownerId`. |

A resource nested under `/salons/{salonId}/...` that exists but belongs to
a *different* salon returns `404`, not `403` — this project treats
cross-tenant existence as something not to confirm or deny (OWASP API1:
Broken Object Level Authorization mitigation), consistent everywhere a path
carries a parent id.

### Error format

Every error response — validation, not-found, access-denied, conflict, or
an unexpected server error — uses one consistent shape (kept as-is rather
than migrated to RFC 7807 `application/problem+json`, since the shipped
Android client already parses this shape and the milestone's backward-compatibility
requirement takes priority over the RFC):

```json
{
  "timestamp": "2026-08-02T10:15:00.123Z",
  "status": 404,
  "error": "Not Found",
  "message": "Salon not found: 3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "path": "/api/v1/salons/3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "traceId": "b6e6c3d2-9e33-4c2b-9a2e-1e7a6f9d2b41"
}
```

`traceId` is new in this milestone — quote it when reporting an issue; the
server logs the full exception against the same id. Status-code semantics
used throughout:

| Status | Meaning |
|---|---|
| `200` | Successful read, update, or state-transition |
| `201` | Resource created |
| `204` | Resource deactivated/removed, no body |
| `400` | Validation failure, malformed body/param, or a domain invariant (`IllegalArgumentException`) |
| `401` | Missing, malformed, expired, or wrong-type bearer token |
| `403` | Authenticated, but not authorized for this action (ownership check failed) |
| `404` | No such resource, or it exists but doesn't belong to the salon in the path |
| `405` | HTTP method not supported on this path |
| `409` | State conflict — double-booking, invalid status transition, duplicate email, idempotency-key reuse with a different body |
| `500` | Unexpected server error — message is always the generic `"An unexpected error occurred"`; never leaks exception internals (OWASP API9) |

Prior to this milestone, several exception types (missing query param,
malformed JSON body, wrong path-variable type, unsupported HTTP method, any
truly unhandled exception) fell through to Spring Boot's default error
page instead of this shape. All are now mapped, so every error response
from this API is guaranteed to be `ApiError` JSON.

### Pagination, filtering, sorting

Endpoints whose result set can grow unbounded — browsing salons, a salon's
bookings, a customer's bookings — are paginated and return this envelope:

```json
{
  "content": [ /* ... */ ],
  "page": 0,
  "size": 20,
  "totalElements": 57,
  "totalPages": 3
}
```

Query parameters: `page` (0-based, default `0`), `size` (default `20`, max
`100` — a request above the max returns `400`), a resource-specific filter,
and `sortDirection` (`asc`/`desc`, case-insensitive). Each paginated
resource sorts by one curated field rather than an arbitrary
client-supplied column — accepting a raw field name into a dynamic sort
would be an OWASP-relevant injection/DoS surface, so this is deliberate,
not an oversight.

Small, inherently-bounded collections — a salon's branches/categories/specialists,
a specialist's weekly schedule/overrides/leaves/blocks — are returned as
plain JSON arrays, no envelope, no pagination. Introducing pagination there
would be pure overhead: none of these can realistically exceed a few dozen
rows per parent.

### Idempotency

`POST /api/v1/bookings` accepts an optional `Idempotency-Key` request
header (any client-generated string, e.g. a UUID). Behavior:

- **Key omitted** — unchanged from before this milestone; every request creates a booking.
- **Key present, first use** — booking is created normally; the `(key, response)` pair is stored for 24h.
- **Key present, replayed with an identical body** — the original `201` response is returned verbatim; **no second booking is created**.
- **Key present, replayed with a different body** — `409 Conflict`; the key is not reusable for a different payload.

No other endpoint carries idempotency-key support: mutations elsewhere are
either naturally idempotent (`PUT` updates, deactivations) or low-stakes,
owner-only administrative actions where an accidental duplicate is
trivially visible and correctable by the owner. Booking creation is the one
customer-facing create where a network retry silently duplicating a
reservation is a real cost, hence the scoped investment.

---

## Authentication

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/api/v1/auth/register` | Public | Create an account |
| POST | `/api/v1/auth/login` | Public | Exchange credentials for a token pair |
| POST | `/api/v1/auth/refresh` | Public (refresh token is the credential) | Exchange a refresh token for a new pair |
| GET | `/api/v1/users/me` | Any authenticated user | The caller's own profile |

Two JWTs are issued together: a short-lived **access token** (default 15
min) for calling the API, and a longer-lived **refresh token** (default 30
days) for obtaining a new pair. Each token embeds its own type — a refresh
token sent to a protected endpoint, or an access token sent to
`/auth/refresh`, is rejected with `401`.

**`POST /api/v1/auth/register`** — Request:
```json
{ "email": "jane.doe@example.com", "password": "supersecret123", "fullName": "Jane Doe", "role": "CUSTOMER" }
```
`role` ∈ `CUSTOMER` \| `MANAGER` \| `SPECIALIST`. `201` → `UserResponse`. Errors: `400` validation, `409` email already registered.

**`POST /api/v1/auth/login`** — Request: `{ "email": "...", "password": "..." }`. `200` → `AuthResponse` (below). Errors: `401` invalid credentials, `403` account deactivated.

**`POST /api/v1/auth/refresh`** — Request: `{ "refreshToken": "..." }`. `200` → `AuthResponse`. Errors: `401` invalid/expired/wrong-type token, `403` account deactivated, `404` account no longer exists.

`AuthResponse`:
```json
{
  "user": { "id": "...", "email": "jane.doe@example.com", "fullName": "Jane Doe", "role": "CUSTOMER" },
  "accessToken": "eyJhbGciOiJIUzM4NCJ9...",
  "accessTokenExpiresAt": "2026-08-02T10:15:00Z",
  "refreshToken": "eyJhbGciOiJIUzM4NCJ9...",
  "refreshTokenExpiresAt": "2026-09-01T09:45:00Z"
}
```

**`GET /api/v1/users/me`** — `200` → `UserResponse`. Errors: `401`.

---

## Salon

Owner-authored business listing. `Salon.ownerId` is the anchor for every
ownership check across the whole nested resource tree below it.

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/api/v1/salons` | Any authenticated user (becomes owner) | Create |
| GET | `/api/v1/salons` | Any authenticated user | Browse active salons — **paginated**, `?name=` filter, `?sortDirection=` (by name) |
| GET | `/api/v1/salons/mine` | Any authenticated user | Salons owned by the caller (unpaginated — bounded per owner) |
| GET | `/api/v1/salons/{salonId}` | Any authenticated user | Get by id |
| PUT | `/api/v1/salons/{salonId}` | Owner only | Full update |
| DELETE | `/api/v1/salons/{salonId}` | Owner only | Soft-deactivate (`204`) |

`CreateSalonRequest` / `UpdateSalonRequest`: `name` (required, ≤255),
`description` (optional, ≤2000), `phone` (required, ≤32), `email`
(optional, valid email), `address` (required, ≤500).

`SalonResponse`: `id`, `ownerId`, `name`, `description`, `phone`, `email`,
`address`, `active`, `createdAt`, `updatedAt`.

---

## Branch

Modeled ahead of any multi-location logic actually consuming it yet — a
salon can grow into several physical locations without a later schema
change. Not yet referenced by services/specialists/bookings.

| Method | Path | Auth |
|---|---|---|
| POST | `/api/v1/salons/{salonId}/branches` | Owner only |
| GET | `/api/v1/salons/{salonId}/branches` | Any authenticated user |
| GET | `/api/v1/salons/{salonId}/branches/{branchId}` | Any authenticated user |
| PUT | `/api/v1/salons/{salonId}/branches/{branchId}` | Owner only |
| DELETE | `/api/v1/salons/{salonId}/branches/{branchId}` | Owner only (`204`) |

Request (`Create`/`Update`): `name` (required, ≤255), `address` (required, ≤500), `phone` (required, ≤32).
Response (`BranchResponse`): `id`, `salonId`, `name`, `address`, `phone`, `active`, `createdAt`, `updatedAt`.

---

## Service Categories

Groups a salon's services (e.g. "Hair", "Nails").

| Method | Path | Auth |
|---|---|---|
| POST | `/api/v1/salons/{salonId}/categories` | Owner only |
| GET | `/api/v1/salons/{salonId}/categories` | Any authenticated user |
| GET | `/api/v1/salons/{salonId}/categories/{categoryId}` | Any authenticated user |
| PUT | `/api/v1/salons/{salonId}/categories/{categoryId}` | Owner only |
| DELETE | `/api/v1/salons/{salonId}/categories/{categoryId}` | Owner only (`204`) |

Request: `name` (required, ≤255), `description` (optional, ≤2000).
Response (`ServiceCategoryResponse`): `id`, `salonId`, `name`, `description`, `active`, `createdAt`, `updatedAt`.

---

## Services

A bookable offering under a category; `durationMinutes` and `price` drive
booking end-time computation and slot generation.

| Method | Path | Auth |
|---|---|---|
| POST | `/api/v1/salons/{salonId}/categories/{categoryId}/services` | Owner only |
| GET | `/api/v1/salons/{salonId}/categories/{categoryId}/services` | Any authenticated user |
| GET | `/api/v1/salons/{salonId}/categories/{categoryId}/services/{serviceId}` | Any authenticated user |
| PUT | `/api/v1/salons/{salonId}/categories/{categoryId}/services/{serviceId}` | Owner only |
| DELETE | `/api/v1/salons/{salonId}/categories/{categoryId}/services/{serviceId}` | Owner only (`204`) |

Request: `name` (required, ≤255), `description` (optional, ≤2000),
`durationMinutes` (required, positive integer), `price` (required, decimal
> 0). Response (`ServiceResponse`) adds `id`, `salonId`, `categoryId`,
`active`, `createdAt`, `updatedAt`.

---

## Specialists

Staff profile. `userId` is an optional link to an existing app account —
salons can list staff who don't have (or need) their own login.

| Method | Path | Auth |
|---|---|---|
| POST | `/api/v1/salons/{salonId}/specialists` | Owner only |
| GET | `/api/v1/salons/{salonId}/specialists` | Any authenticated user |
| GET | `/api/v1/salons/{salonId}/specialists/{specialistId}` | Any authenticated user |
| PUT | `/api/v1/salons/{salonId}/specialists/{specialistId}` | Owner only |
| DELETE | `/api/v1/salons/{salonId}/specialists/{specialistId}` | Owner only (`204`) |

Request (`Create`): `userId` (optional UUID, must reference an existing
user if present), `displayName` (required, ≤255), `bio` (optional,
≤2000), `photoUrl` (optional, ≤1000). `Update` omits `userId` (immutable
after creation). Response (`SpecialistResponse`): `id`, `salonId`,
`userId`, `displayName`, `bio`, `photoUrl`, `active`, `createdAt`, `updatedAt`.

---

## Availability

Three layers: a salon's **working hours**, a specialist's **schedule**
(weekly pattern + overrides + leave + manual blocks), and the **computed
slots** a customer can actually book, which combine both.

### Working hours (salon-level)

Weekly schedule; each day may hold multiple non-overlapping intervals
(e.g. split by a lunch break). No row for a day means the salon is closed
that day.

| Method | Path | Auth |
|---|---|---|
| PUT | `/api/v1/salons/{salonId}/working-hours/{dayOfWeek}` | Owner only — upsert |
| GET | `/api/v1/salons/{salonId}/working-hours` | Any authenticated user — all configured days |
| GET | `/api/v1/salons/{salonId}/working-hours/{dayOfWeek}` | Any authenticated user |
| DELETE | `/api/v1/salons/{salonId}/working-hours/{dayOfWeek}` | Owner only — marks the salon closed that day (`204`) |

`dayOfWeek` path segment: `MONDAY`…`SUNDAY`. Request/response `intervals`:
`[{ "start": "09:00:00", "end": "17:00:00" }, ...]` (non-empty, non-overlapping).

### Specialist schedule

All under `/api/v1/salons/{salonId}/specialists/{specialistId}/schedule/`.

| Sub-resource | Method | Path suffix | Auth | Notes |
|---|---|---|---|---|
| Weekly availability | PUT | `weekly-availability/{dayOfWeek}` | Owner only | Upsert the specialist's recurring pattern for a day |
| | GET | `weekly-availability` \| `weekly-availability/{dayOfWeek}` | Any authenticated user | |
| | DELETE | `weekly-availability/{dayOfWeek}` | Owner only (`204`) | |
| Overrides | PUT | `overrides/{date}` | Owner only | One-off replacement of the weekly pattern for a specific date; empty `intervals` = full day off |
| | GET | `overrides` | Any authenticated user | `reason` redacted for non-owners |
| | DELETE | `overrides/{overrideId}` | Owner only (`204`) | |
| Leave | POST | `leaves` | Owner only | A date range (inclusive) the specialist is fully unavailable |
| | GET | `leaves` | Any authenticated user | `reason` redacted for non-owners |
| | DELETE | `leaves/{leaveId}` | Owner only (`204`) | |
| Manual blocks | POST | `blocks` | Owner only | An ad-hoc blocked window on one date, without taking full-day leave |
| | GET | `blocks` | Any authenticated user | `reason` redacted for non-owners |
| | DELETE | `blocks/{blockId}` | Owner only (`204`) | |

**Redaction (added this milestone):** `reason` on overrides, leaves, and
blocks can carry sensitive personal context (e.g. "medical leave"). It's
only populated in the response for the salon's owner; every other viewer
sees `"reason": null` even though the record itself (dates/times) is
visible. This closes an OWASP API3 (Excessive Data Exposure) gap — these
list endpoints previously returned `reason` to any authenticated caller.

Leave request: `{ "startDate": "2026-08-10", "endDate": "2026-08-15", "reason": "Vacation" }`.
Block request: `{ "date": "2026-08-10", "start": "14:00:00", "end": "15:00:00", "reason": "Personal" }`.
Override request: `{ "date": "2026-08-10", "intervals": [...], "reason": "Holiday hours" }`.

### Computed available slots

| Method | Path | Auth |
|---|---|---|
| GET | `/api/v1/salons/{salonId}/specialists/{specialistId}/available-slots` | Any authenticated user |

Query params (all required except `slotIntervalMinutes`): `serviceId`
(UUID), `date` (ISO date), `slotIntervalMinutes` (default `15`).

Response: `[{ "start": "2026-08-10T09:00:00", "end": "2026-08-10T09:30:00" }, ...]`.

Computed as: (salon working hours for that day-of-week) ∩ (specialist's
override for that date, or their weekly pattern if no override) − (manual
blocks for that date) − (existing pending/confirmed bookings) — or empty
entirely if the specialist has approved leave covering the date. Slot
width equals the service's `durationMinutes`; candidate start times step by
`slotIntervalMinutes`. **This is advisory, not enforced**: `POST
/api/v1/bookings` does not itself re-validate that the requested time falls
within a computed slot — it trusts the client picked from this endpoint,
and the double-booking guarantee below is what actually prevents overlap.

---

## Booking

The only two-sided resource in this API — created by a customer, then
confirmed/completed by the salon owner, with either party able to cancel or
reschedule.

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/api/v1/bookings` | Any authenticated user (becomes the customer) | Supports `Idempotency-Key` (see [Conventions](#idempotency)) |
| GET | `/api/v1/bookings/mine` | Any authenticated user | The caller's own bookings — **paginated**, `?status=` filter, `?sortDirection=` (by start time) |
| GET | `/api/v1/bookings/{bookingId}` | Customer or owner | |
| GET | `/api/v1/salons/{salonId}/bookings` | Owner only | All of a salon's bookings — **paginated**, `?status=` filter |
| PATCH | `/api/v1/bookings/{bookingId}/confirm` | Owner only | `PENDING` → `CONFIRMED` |
| PATCH | `/api/v1/bookings/{bookingId}/cancel` | Customer or owner | `PENDING`/`CONFIRMED` → `CANCELLED` |
| PATCH | `/api/v1/bookings/{bookingId}/complete` | Owner only | `CONFIRMED` → `COMPLETED` |
| PUT | `/api/v1/bookings/{bookingId}/reschedule` | Customer or owner | Moves the time window; re-checked for conflicts |

Status lifecycle: `PENDING → CONFIRMED → COMPLETED`, with `CANCELLED`
reachable from `PENDING` or `CONFIRMED`. An out-of-order transition (e.g.
confirming an already-cancelled booking) returns `409`.

`CreateBookingRequest`: `salonId`, `serviceId`, `specialistId` (all UUID,
required), `startTime` (ISO local date-time, required, must be in the
future — the server computes `endTime` from the service's
`durationMinutes`), `notes` (optional, ≤1000).

`RescheduleBookingRequest`: `{ "newStartTime": "..." }` — `endTime` is
recomputed from the same service duration.

`BookingResponse`: `id`, `salonId`, `serviceId`, `specialistId`,
`customerId`, `startTime`, `endTime`, `status`, `notes`, `createdAt`,
`updatedAt`.

### Conflict detection (unchanged this milestone, documented here for completeness)

`POST /bookings` and the reschedule endpoint both route through a single
atomic reservation path: a Postgres transaction-scoped advisory lock keyed
by `specialistId` serializes concurrent attempts for the same specialist,
then an overlap query runs inside that lock before the row is written. Two
customers racing for the same specialist/time always resolve to exactly
one `201` and the rest `409` — proven under real concurrent HTTP load in
`BookingConflictConcurrencyIntegrationTest`.

---

## Known gaps

Carried over from the auth milestone, plus one noted above:

- No refresh-token revocation/rotation tracking — a stolen refresh token
  remains valid until it expires. Would need a persisted token/session record to revoke.
- No rate limiting anywhere (OWASP API4: Unrestricted Resource
  Consumption). The `size` pagination cap (max 100) bounds the cost of a
  single request, but repeated requests aren't throttled. Out of scope for
  this milestone — would need a decision on a backing store (Redis is
  already wired for caching but unused; a token-bucket limiter there is the
  natural next step).
- `POST /api/v1/bookings`'s idempotency-key store has a narrow race: two
  truly concurrent first-time requests with the *same* key can both reach
  the use case before either's key is persisted, so the second `store()`
  call can violate the key's uniqueness constraint. This doesn't allow a
  double-booking — the advisory-lock reservation path still guarantees
  that — it only means the idempotency replay guarantee itself has a rare
  edge case under simultaneous first use. Acceptable for now; would need an
  `INSERT ... ON CONFLICT` upsert to fully close.
- Booking creation doesn't itself re-validate that the requested time falls
  within a computed available slot (see [Availability](#computed-available-slots)) — it trusts the client and
  relies on conflict detection alone to prevent overlap.

---

*Last updated: API-hardening milestone (pagination/filtering/sorting,
`Idempotency-Key` on booking creation, `ApiError.traceId`, broadened
exception handling, and the leave/override/block `reason` redaction fix).*
