# ROJAN Customer CRM — Backend Architecture Plan v1

**Priority:** P0
**Status:** Phase 1 — Audit & Architecture Plan Only. No code written. Awaiting approval before implementation.
**Constraint compliance:** Everything below is additive (new tables, new module, new endpoints). Nothing in `bookings`, `BookingController`, `SalonBookingController`, `BookingRepository`, or any Owner App Booking file is touched or assumed to change.

---

## 0. Audit findings (what exists today)

### Backend (`ROJAN_Backend`)

- **User model** (`domain/user/User.kt`, table `users`, `V1__init_schema.sql` + `V5__mobile_authentication.sql`): `id, email?, passwordHash?, phoneNumber?, fullName, role (CUSTOMER|MANAGER|SPECIALIST), active, createdAt, updatedAt`. Email and password are nullable since Mobile Auth Phase 1; a `CHECK (email IS NOT NULL OR phone_number IS NOT NULL)` constraint guarantees at least one identity anchor. **This is the only "customer" concept that exists today** — a `User` row with `role = CUSTOMER`, created either via `/auth/register` (email/password) or auto-created on first successful `/auth/otp/verify` (phone-only, name defaults to "ROJAN User" if none supplied). No CRM fields exist anywhere on it: no company, no lifetime value, no status/lifecycle, no notes, no tags, no timeline.
- **No dedicated Customer table, entity, repository, or endpoint exists anywhere** in `domain`/`application`/`api`/`infrastructure`. Confirmed by direct inspection of every migration (`V1`–`V5`) and every controller in `api/`.
- **Booking's customer reference** (`V3__booking_engine_schema.sql`): `bookings.customer_id UUID NOT NULL REFERENCES users (id)`. A booking always points at a real `User` row — there is no separate customer identity a booking can reference. `BookingRepository.findByCustomerId(customerId, pageRequest, statusFilter, sortDirection)` already exists at the port level and today powers only the self-service `GET /bookings/mine` (the caller's own bookings) — **no owner-facing "bookings for this specific customer" endpoint exists yet** (this was flagged as a gap in `ROJAN_Booking_CRM_Integration_Plan_v1.md` and is resolved by this plan, see §2).
- **Authentication/tenant relation**: confirmed unchanged and directly reusable. JWT carries only `sub = userId`, no salon/tenant claim. Every salon-scoped resource (`Booking`, `Specialist`, `Service`) re-derives ownership per request via `Salon.ownerId == callerId`, checked inline in the use case/controller (never `@PreAuthorize`, never cached). `Specialist` already has the exact nullable-account-link shape a CRM Customer needs: `specialists.user_id UUID REFERENCES users (id)` (nullable — a specialist may or may not have a login). This plan reuses that precedent directly rather than inventing a new pattern.

### Owner App (`ROJAN_Desktop`)

- **Customer domain model** (`Domain/Customers/Customer.cs`): `Id, FullName, Company, Email, Phone, Status (Lead|Prospect|Active|Vip|Inactive|Churned), LifetimeValue (formatted string), LastContactedAt, Notes, OrganizationId, BranchId` — a genuinely CRM-shaped record, well-designed, with a non-terminal status lifecycle (`CustomerRules.IsValidTransition`, every status has a way out, including a `Churned → Lead` win-back path). Related child concepts, each their own record: `CustomerNote(Id, CustomerId, Text, CreatedAt)`, `CustomerTag(Id, CustomerId, Label, CreatedAt)`, `CustomerActivity(Id, CustomerId, Description, OccurredAt)` (the timeline event source).
- **Repository**: `ICustomerRepository` — 11 methods (list, get-by-id, get-notes, get-tags, get-activity, create, update, add-note, add-tag, remove-tag, add-activity). Currently backed by `EfCustomerRepository` (local SQLite) — the same "Local, not Backend" state confirmed in the Booking plan.
- **Screens**: `CustomerPageViewModel` (list/search/create, 4 filters: search text, company, tag, status) + `CustomerProfileViewModel` (full "Customer 360": notes, tags, **timeline already exists** as an `ObservableCollection<TimelineEntry>`, **booking history already exists** — composed today by filtering `IBookingQueryService.GetBookingsAsync()` where `booking.CustomerId == customerId`, split into upcoming/past — plus customer scoring/loyalty/engagement computed client-side). **None of this UI needs to be built** — it already exists and already expects exactly the shape this plan proposes; it just has nowhere real to read from today.
- **Required Owner App capability, confirmed**: the UI already assumes a backend that can answer "list my salon's customers," "get one customer's full profile," "get their notes/tags," "get their timeline," and "get their bookings" — this plan's API surface is sized to match that existing UI contract, not to invent new UI requirements.

---

## 1. Customer Domain Model

### 1.1 The central design decision

**`Customer` is a new, salon-scoped aggregate, distinct from `User` — not a repurposing of `User`.** It has an **optional** link to a `User` account (`userId: UserId?`), mirroring the exact precedent `Specialist` already establishes in this codebase (`specialists.user_id`, nullable). This is deliberate, not a shortcut:

- A real salon has customers who **never** create an app account — walk-ins, phone bookings taken by the receptionist, regulars the owner adds manually. These are real CRM subjects from day one, with a name/phone/notes/tags/status, even though no `User` row exists for them.
- A customer who **does** sign up via Mobile OTP (or email/password) gets a real `User` row automatically (existing Mobile Auth behavior) — that `User` can be linked to a `Customer` record, at which point booking history becomes resolvable (see §1.3 and Risk #1).
- Collapsing "Customer" into "User" (e.g., adding CRM columns directly to `users`) would be wrong: `User` is an authentication identity shared across every salon a person interacts with (a customer could book at two different salons), while CRM data (status, notes, tags, lifetime value) is **owned by one salon** and must never leak to another (see §4). A salon-scoped `Customer` row per salon-per-person is the only model that keeps that boundary honest.

### 1.2 Required concepts, mapped to fields

| Ticket concept | Design |
|---|---|
| **Customer Profile** | `Customer` aggregate: `id, salonId, userId? (nullable link to an account), fullName, phoneNumber?, email?, company?, status, active, createdAt, updatedAt`. At least one of `phoneNumber`/`email` required (same `CHECK` pattern `users` already uses). |
| **Customer Contact** | `phoneNumber`/`email` on `Customer` itself — reusing the existing `PhoneNumber` value class (E.164 validation, already built for Mobile Auth) for consistency and to reduce duplicate-record risk (see Risk #5). |
| **Customer History** | Booking history specifically — `GET .../customers/{id}/bookings`, reusing the *existing* `BookingResponse` shape (no new DTO). A strict subset of Timeline, focused on service consumption only. |
| **Customer Timeline** | A **read-time merged feed**, not a single physically-written table — see §1.4 for why. |
| **Customer Tags** | New `customer_tags` table, direct mirror of the Owner App's existing `CustomerTag(id, customerId, label, createdAt)`. |
| **Customer Notes** | New `customer_notes` table, direct mirror of `CustomerNote(id, customerId, text, createdAt)`, plus a backend-only `authorId` (which staff member wrote it) since the backend serves multiple logins per salon, unlike the single-owner desktop fake — additive, does not break the existing client shape. |
| **Customer Value / Lifetime Value** | **Computed on read**, not stored — see §1.5. |

Status lifecycle: reuse the Owner App's existing `CustomerStatus`/`CustomerRules` design as-is (`Lead, Prospect, Active, Vip, Inactive, Churned`, non-terminal, `Churned → Lead` win-back path) — it's already well-designed and the client already expects exactly these six values. The backend becomes the authoritative enforcer of `IsValidTransition`, the same "domain enforces the state machine, use case calls it, controller never re-validates" pattern `BookingRules`/`Booking.confirm()`/`cancel()` already establish.

### 1.3 Relationship to Booking (no schema change to `bookings`)

`Booking.customerId` continues to reference `users.id` exactly as it does today — **zero changes to the Booking schema or contract**. The `Customer ↔ Booking` link is transitive: `customers.user_id → users.id ← bookings.customer_id`. A `Customer` row's booking history is only resolvable when `customers.user_id IS NOT NULL`. This is the most important architectural consequence in this whole plan — see **Risk #1**.

### 1.4 Why Timeline is computed, not stored

A naive design would write a `customer_activities` row from every relevant use case (note added, tag added, status changed, booking completed) — but that couples the Booking module to the Customer module (every booking-status-transition use case would need to remember to also write a Customer-side activity row), and a missed write silently produces an incomplete timeline with no way to detect the gap. Instead:

- `customer_activities` stores only **manually-meaningful events** the CRM module itself produces (status changes, tag added/removed) — written by the Customer module alone, no cross-module coupling.
- `GET .../customers/{id}/timeline` **merges at read time**: `customer_activities` rows + `customer_notes` (each note is itself a timeline entry) + booking lifecycle events queried directly from `bookings` via the linked `userId` (created/confirmed/completed/cancelled, using data that already exists and is already correct — no risk of drift). One paginated, chronologically-sorted response, assembled by the query, not by scattered writes.

### 1.5 Why Lifetime Value is computed, not stored

The Owner App's current fake/local `LifetimeValue` is a hand-typed formatted string with no real computation behind it. The backend should never do that. Phase 1 proposal: compute it on read — `SUM(service.price)` over every `COMPLETED` booking for the customer's linked `userId` within that salon (same join `GetDashboardInsightsUseCase` already performs for its own aggregates, no new query pattern). No stored/cached column, no staleness risk, correct by construction. If this becomes a measurable performance concern at scale (a customer with hundreds of completed bookings, recomputed on every profile view), a cached column refreshed on booking completion is a natural, isolated future optimization — explicitly not needed for Phase 1's expected data volumes.

---

## 2. Backend APIs Required

All endpoints follow the exact conventions `SalonBookingController`/`BookingController` already establish: Bearer JWT, salon ownership re-checked per request (never cached), `PagedResponse<T>` envelope for lists, errors via the existing `GlobalExceptionHandler`/`ApiError` shape with new `errorCode`s added to the same `when` block (no new error-handling mechanism).

**A hard authorization rule distinct from Booking**: unlike a booking (readable by either the customer *or* the owner), **every Customer CRM endpoint is owner-only.** A customer must never see their own CRM record — their status, tags, or a note like "chargeback risk, handle carefully" is internal business data, not something to expose to the person it's about. There is no customer-facing read path in this design at all.

### The 6 requested endpoints

| Endpoint | Method | Request DTO | Response DTO | Authorization | Tenant Scope |
|---|---|---|---|---|---|
| `/api/v1/salons/{salonId}/customers` | GET | query: `page=0, size=20, status?, tag?, search?` | `PagedResponse<CustomerResponse>` | Bearer JWT, salon owner only | `salonId` in path, `salon.ownerId == callerId` checked per request |
| `/api/v1/salons/{salonId}/customers/{customerId}` | GET | — | `CustomerResponse` | Bearer JWT, salon owner only | Same, plus `customer.salonId == salonId` |
| `/api/v1/salons/{salonId}/customers/{customerId}/timeline` | GET | query: `page=0, size=20` | `PagedResponse<CustomerTimelineEntryResponse>` | Bearer JWT, salon owner only | Same |
| `/api/v1/salons/{salonId}/customers/{customerId}/bookings` | GET | query: `page=0, size=20, status?` | `PagedResponse<BookingResponse>` (existing shape, reused) | Bearer JWT, salon owner only | Same — resolves via `BookingRepository.findByCustomerId(customer.userId, ...)`; empty page (not an error) if `customer.userId` is null |
| `/api/v1/salons/{salonId}/customers` | POST | `CreateCustomerRequest{fullName, phoneNumber?, email?, company?}` | 201 `CustomerResponse` | Bearer JWT, salon owner only | `salonId` from path, stamped onto the new row |
| `/api/v1/salons/{salonId}/customers/{customerId}` | PATCH | `UpdateCustomerRequest{fullName?, phoneNumber?, email?, company?, status?}` (partial - only supplied fields change) | `CustomerResponse` | Bearer JWT, salon owner only | Same |

### DTO shapes proposed

```kotlin
data class CustomerResponse(
    val id: UUID, val salonId: UUID, val userId: UUID?,
    val fullName: String, val phoneNumber: String?, val email: String?, val company: String?,
    val status: CustomerStatus, val lifetimeValue: BigDecimal, // computed, see §1.5
    val tags: List<String>, val active: Boolean,
    val createdAt: Instant, val updatedAt: Instant,
)

data class CustomerTimelineEntryResponse(
    val type: String, // NOTE | TAG_ADDED | TAG_REMOVED | STATUS_CHANGED | BOOKING_CREATED | BOOKING_CONFIRMED | BOOKING_COMPLETED | BOOKING_CANCELLED
    val description: String,
    val occurredAt: Instant,
)

data class CreateCustomerRequest(val fullName: String, val phoneNumber: String?, val email: String?, val company: String?)
data class UpdateCustomerRequest(val fullName: String?, val phoneNumber: String?, val email: String?, val company: String?, val status: CustomerStatus?)
```

Error codes (added to the existing `GlobalExceptionHandler`, same pattern as `BOOKING_NOT_FOUND`/`SALON_NOT_FOUND`): `CUSTOMER_NOT_FOUND` (404), `CUSTOMER_ACCESS_DENIED` (403, reuses the same shape as `SalonAccessDeniedException`), `INVALID_CUSTOMER_STATE` (409, illegal status transition — mirrors `INVALID_BOOKING_STATE`), `CUSTOMER_ALREADY_EXISTS` (409, duplicate phone within the same salon — see Risk #5), `VALIDATION_FAILED` (400, existing, reused as-is).

### Recommended additional endpoints (not in the named 6, needed for Notes/Tags to actually be usable)

Section 1 of this ticket names **Customer Tags** and **Customer Notes** as required concepts, but the named 6 endpoints have no write path for either. Proposing these as the natural completion, same phase, but flagging them explicitly rather than silently expanding scope — confirm before implementation:

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/v1/salons/{salonId}/customers/{customerId}/notes` | POST | Add a note (`{text}`) |
| `/api/v1/salons/{salonId}/customers/{customerId}/tags` | POST | Add a tag (`{label}`) |
| `/api/v1/salons/{salonId}/customers/{customerId}/tags/{tagId}` | DELETE | Remove a tag |

---

## 3. Database Design

New migration, purely additive — **no existing table is altered**.

```sql
-- V6__customer_crm_schema.sql

CREATE TABLE customers
(
    id           UUID PRIMARY KEY,
    salon_id     UUID         NOT NULL REFERENCES salons (id),
    user_id      UUID REFERENCES users (id),              -- nullable: walk-in customers have no account (see §1.3, Risk #1)
    full_name    VARCHAR(255) NOT NULL,
    phone_number VARCHAR(20),
    email        VARCHAR(255),
    company      VARCHAR(255),
    status       VARCHAR(16)  NOT NULL DEFAULT 'LEAD',
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (phone_number IS NOT NULL OR email IS NOT NULL)
);
CREATE INDEX idx_customers_salon_id ON customers (salon_id);
CREATE INDEX idx_customers_user_id ON customers (user_id);
-- Same-salon duplicate-phone guard (see Risk #5) - does not prevent cross-salon duplicates, which is correct: the same person is a distinct CRM subject per salon.
CREATE UNIQUE INDEX uq_customers_salon_phone ON customers (salon_id, phone_number) WHERE phone_number IS NOT NULL;

CREATE TABLE customer_tags
(
    id          UUID PRIMARY KEY,
    customer_id UUID         NOT NULL REFERENCES customers (id),
    label       VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_customer_tags_customer_id ON customer_tags (customer_id);

CREATE TABLE customer_notes
(
    id          UUID          PRIMARY KEY,
    customer_id UUID          NOT NULL REFERENCES customers (id),
    author_id   UUID          NOT NULL REFERENCES users (id),
    text        VARCHAR(2000) NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_customer_notes_customer_id ON customer_notes (customer_id);

CREATE TABLE customer_activities
(
    id          UUID         PRIMARY KEY,
    customer_id UUID         NOT NULL REFERENCES customers (id),
    type        VARCHAR(32)  NOT NULL,   -- STATUS_CHANGED | TAG_ADDED | TAG_REMOVED
    description VARCHAR(500) NOT NULL,
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_customer_activities_customer_id ON customer_activities (customer_id);
```

### Relations, explicit

```
Salon (1) ──< Customer (many)          -- customers.salon_id
User  (0..1) ──< Customer (many)       -- customers.user_id, nullable
Customer (1) ──< CustomerTag (many)
Customer (1) ──< CustomerNote (many)
Customer (1) ──< CustomerActivity (many)
Customer (via user_id) ··· Booking     -- transitive only: customers.user_id -> users.id <- bookings.customer_id, NOT a direct FK
```

`Service History` (named in §3 of the ticket) is not a separate table — it is the booking-history query (§1.3/§2), since "service history" and "booking history" are the same underlying data (a completed booking already records which service was rendered).

---

## 4. Multi-Tenant Rules

Reuses the exact, already-proven pattern — no new tenancy concept introduced:

```
User (JWT sub, no salon claim)
  │
  │  resolved fresh, every request, never cached
  ▼
Salon.ownerId == callerId  →  403 CUSTOMER_ACCESS_DENIED otherwise
  │
  ▼
Customer.salonId == {salonId in path}  →  404 CUSTOMER_NOT_FOUND otherwise
```

Every Customer query is written salon-scoped at the repository level (`findBySalonId`, never a global `findAll`), the same discipline `BookingRepository`/`SpecialistRepository` already enforce — **there is no code path that can return a customer belonging to a different salon**, by construction, not by a filter that could be forgotten. This is the concrete answer to "Customer data must never leak between salons": it isn't a policy layered on top, it's the shape of every query.

One rule stated explicitly because it's easy to get wrong by analogy with Booking: **Customer endpoints are owner-only, full stop — there is no "customer or owner" dual-access mode here**, unlike Booking's `requireCustomerOrOwner`. A `User` who happens to also be a `Customer` record's linked account has no read access to that CRM record via these endpoints.

---

## 5. AI Preparation (not implemented now — schema readiness only)

| Future capability | What this schema already provides |
|---|---|
| Recommendation Engine | `Customer ↔ Booking` (via `userId`) gives service-consumption history per customer per salon; `tags`/`status` give segments to condition on |
| Retention prediction | `customer_activities` is a timestamped state-change audit trail (status history over time); last-booking date derivable from linked bookings |
| Loyalty | Lifetime value (§1.5) + booking frequency, both derivable without new columns |
| Marketing automation | `phoneNumber`/`email` + `tags` already model segmentable contact lists |

The honest limitation to carry forward: **every one of these features is only meaningful for the linked subset of customers** (`user_id IS NOT NULL`) — see Risk #1. A recommendation engine has nothing to recommend to a walk-in customer with no booking history on file. No feature/embedding generation, model training, or scoring pipeline is proposed or implied here — this section only confirms the schema doesn't need to change later to support that work when it's scoped.

---

## 6. Migration Strategy

1. Single new Flyway migration, `V6__customer_crm_schema.sql` (§3) — four new tables, zero changes to any existing table. `baseline-on-migrate` and the existing `ddl-auto: validate` posture are unaffected.
2. New Kotlin module code follows the exact existing package layout: `domain/customer/` (Customer, CustomerId, CustomerStatus, CustomerRepository port), `application/customer/` (use cases), `infrastructure/persistence/customer/` (JPA entity + adapter), `api/customer/` (controller + DTOs) — mirrors `salon`/`booking` module structure precisely, no new architectural pattern introduced.
3. **No backfill needed** — this is new data, not a migration of existing data into a new shape. Existing `users`/`bookings`/`salons` rows are untouched and require no data migration.
4. **Identity reconciliation is explicitly out of scope for Phase 1** (see Risk #2): if a salon owner manually creates a walk-in `Customer` record, and that same person later signs up via Mobile OTP with the same phone number, the two rows are **not** automatically linked — `customers.user_id` stays null. A future "link this customer to an account" endpoint (or an automatic match-by-phone-number job) is a natural Phase 2, deliberately not designed here to avoid scope creep in an already-substantial Phase 1.
5. Rollout order for implementation (once approved): domain model → repository/persistence → use cases → controller/DTOs → tests, same order every prior module in this codebase followed (Booking, Salon, Specialist) — no new process needed.

---

## 7. Risks

1. **(Highest) Walk-in customers can never have real booking history under the current booking-creation model.** `Booking.customerId` requires a real `User`, and `POST /api/v1/bookings` is self-service-only (the caller always becomes the booking's customer — confirmed and already flagged in `ROJAN_Booking_Integration_Implementation_Report_v1.md`). A `Customer` record with no linked `userId` gets a profile, notes, tags, and a timeline of manual entries — but zero booking data, zero computed lifetime value, and nothing for the AI features in §5 to work with. This is not a Customer CRM design flaw; it's a direct consequence of a Booking-module decision made and accepted in the prior phase. **Fully solving it requires an owner-initiated booking creation capability, which is Booking-module scope and explicitly not touched here** ("do not modify Booking Integration"). Flagging this as the plan's single biggest open question: is a coordinated follow-up to Booking acceptable, or should Phase 1 CRM ship knowing a large fraction of real-world customers (walk-ins) will have thin profiles indefinitely?
2. **Identity reconciliation gap** (§6.4) — a manually-created `Customer` and a later real `User` account for the same person are not automatically the same row. No data corruption risk (both rows are independently valid), but a real UX gap (the owner sees what looks like two different people).
3. **Lifetime value computed on read** could become a real cost at scale (large per-customer booking history, recomputed on every profile view) — not a Phase 1 concern at expected data volumes, but worth monitoring; a cached/recomputed-on-completion column is a contained future optimization if it becomes one.
4. **Owner-only visibility must be enforced correctly everywhere**, with zero customer-facing read path — a real privacy requirement (a note or tag about a customer must never be visible to that customer), not just a UX preference. Every endpoint in §2 must be implemented with the owner-only check from day one, not added later.
5. **Duplicate-customer risk.** The `(salon_id, phone_number)` unique index only catches byte-identical phone numbers; inconsistent formatting could still produce near-duplicates. Mitigated by requiring E.164 normalization (reusing the existing `PhoneNumber` value class) at the API boundary — fuzzy/near-duplicate detection is explicitly out of scope.
6. **Zero risk to existing systems** — every change proposed here is additive (new tables, new module, new endpoints). No existing migration, entity, controller, DTO, or test in Booking, Auth, Salon, or Specialist is touched, read, or assumed to change.

---

**No code was written during this phase.** Awaiting your review of §1.1 (the Customer/User separation decision), the recommended additional Notes/Tags endpoints in §2, and — most importantly — Risk #1 (the walk-in/booking-history limitation) before implementation begins.
