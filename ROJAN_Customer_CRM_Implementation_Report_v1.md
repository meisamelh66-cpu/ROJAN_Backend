# ROJAN Customer CRM — Backend Implementation Report v1

**Scope:** Phase 1 implementation of `ROJAN_Customer_CRM_Architecture_Plan_v1.md`, as approved.
**Status:** Complete. `./gradlew clean build` — **BUILD SUCCESSFUL**, 233/233 tests passing (41 new), 0 failures, 0 errors.
**Constraint compliance:** Every change is additive. `bookings`, `BookingController`, `SalonBookingController`, `BookingRepository`'s existing methods, `AuthController`, and the JWT/security pipeline are untouched — the only cross-module touch is `GlobalExceptionHandler.kt`, which gained new `@ExceptionHandler` cases without modifying any existing one.

---

## 1. What was built

The full Customer domain from the approved plan, end to end: domain model → migration → repository → use cases → REST API → tests.

```
Owner (JWT sub, no salon claim)
  -> Salon.ownerId == callerId (checked per request, every endpoint)
  -> Customer.salonId == {salonId in path}
  -> customer_tags / customer_notes / customer_activities (children)
  -> Booking (only reachable via customer.userId, when linked)
```

## 2. Files changed

### Domain (new)
- `domain/customer/Customer.kt` — the aggregate: `CustomerId`, `CustomerStatus` (6-state, non-terminal lifecycle reused as-designed from the Owner App's own `CustomerStatus`), `Customer` itself with inline transition validation (`VALID_TRANSITIONS` map + `changeStatus()`, matching this codebase's own "validate inside the aggregate" convention rather than a separate rules class)
- `domain/customer/CustomerTag.kt`, `CustomerNote.kt`, `CustomerActivity.kt` — child value types, each with their own UUID-backed id
- `domain/customer/CustomerRepository.kt`, `CustomerTagRepository.kt`, `CustomerNoteRepository.kt`, `CustomerActivityRepository.kt` — one port per entity, mirroring the Schedule module's own "several small repositories, not one god-repository" precedent
- `domain/common/CustomerDomainExceptions.kt` — `CustomerNotFoundException`, `CustomerAccessDeniedException`, `InvalidCustomerStateException`, `CustomerAlreadyExistsException`, `CustomerTagNotFoundException`

### Infrastructure (new)
- `infrastructure/persistence/customer/` — `CustomerJpaEntity`/`CustomerTagJpaEntity`/`CustomerNoteJpaEntity`/`CustomerActivityJpaEntity`, one `SpringDataRepository` and one `RepositoryAdapter` per entity, all flat table mappings with zero JPA-managed relationships (matching every existing `*JpaEntity` in this codebase)
- `infrastructure/db/migration/V6__customer_crm_schema.sql` — `customers`, `customer_tags`, `customer_notes`, `customer_activities`, exactly as specified in the approved plan §3. **No existing table altered.**

### Application (new)
- `application/customer/CreateCustomerUseCase.kt`, `UpdateCustomerUseCase.kt` (genuine PATCH/merge semantics, not full-replace), `AddCustomerNoteUseCase.kt`, `AddCustomerTagUseCase.kt`, `RemoveCustomerTagUseCase.kt`, `GetCustomerTimelineUseCase.kt` (the read-time merge the plan specified), `GetCustomerBookingsUseCase.kt` (resolves the plan-flagged missing "bookings for this customer" capability), `CalculateCustomerLifetimeValueUseCase.kt` (computed-on-read, per plan §1.5)

### API (new)
- `api/customer/CustomerDtos.kt`, `CustomerController.kt` — 9 endpoints under `/api/v1/salons/{salonId}/customers` (the 6 named in the ticket + the 3 recommended Notes/Tags write endpoints flagged in the plan, implemented since the domain model is incomplete without them)
- `api/config/CustomerUseCaseConfig.kt` — Spring bean wiring, mirrors `SalonUseCaseConfig`/`BookingUseCaseConfig`

### API (modified)
- `api/common/GlobalExceptionHandler.kt` — 5 new exception types added to the existing `handleNotFound`/`handleAccessDenied`/`handleConflict` handlers and `errorCodeFor` mapping; **one deliberate deviation from the plan**: `CustomerAccessDeniedException` shares the existing `"ACCESS_DENIED"` code (with `Salon`/`BookingAccessDeniedException`) rather than the plan's proposed distinct `CUSTOMER_ACCESS_DENIED` — the codebase's own established convention (one shared code across every access-denied type) took precedence over the plan's draft proposal.

### Tests (new) — 41 tests
- `domain/customer/CustomerTest.kt` — 11 tests: creation invariants, every documented status transition including the `CHURNED → LEAD` win-back path, illegal-jump rejection, `linkToUser`, `update`'s "can't remove both contact methods" guard
- `application/customer/` — 23 tests across 6 files (`CreateCustomerUseCaseTest`, `UpdateCustomerUseCaseTest`, `CustomerNotesAndTagsUseCaseTest`, `GetCustomerTimelineUseCaseTest`, `GetCustomerBookingsUseCaseTest`, `CalculateCustomerLifetimeValueUseCaseTest`) plus `CustomerTestFixtures.kt` (in-memory fakes, reusing `InMemorySalonRepository`/`InMemoryBookingRepository`/`InMemoryServiceRepository` from the existing Salon/Booking test fixtures rather than duplicating them)
- `bootstrap/CustomerCrmFlowIntegrationTest.kt` — 7 tests against a real (embedded, no-Docker) Postgres + the real HTTP layer: full lifecycle (create → list → status change → notes → tags → timeline → tags removed → bookings), duplicate-phone rejection, illegal-status-transition rejection, **cross-tenant isolation** (a customer fetched via a different salon's path returns 404), non-owner rejection (403), unauthenticated rejection (401), OpenAPI doc coverage

## 3. Two real bugs caught by the integration test, not by compilation

Both were only found because a real Postgres instance was actually exercised — worth stating plainly since they'd have shipped silently otherwise:

1. **`COALESCE(c.phoneNumber, '') LIKE ...` inside JPQL threw `function lower(bytea) does not exist` at runtime.** Postgres's `||`/function-argument type inference picked `bytea` for the otherwise-untyped bind parameter in that expression shape. Neither Kotlin nor Hibernate's query validation catches this - JPQL is not checked against the real database until it actually runs.
2. **Even after removing the `COALESCE`, a bare `LOWER(:searchPattern)` with a nullable bind parameter used only inside a function call (never directly compared to a typed column) hit the identical `bytea` inference bug.** Root-caused by testing incrementally rather than guessing: first moved the `%...%` wrapping into Kotlin (ruling out string concatenation as the cause), confirmed the bug persisted, then fixed it for real with an explicit `CAST(:searchPattern AS string)` in the JPQL - the standard, correct fix for "Postgres can't infer a bind parameter's type," now documented in `CustomerSpringDataRepository`'s own doc comment so the next person touching a similar query doesn't rediscover this the hard way.

## 4. Tests

| Suite | Tests | Result |
|---|---|---|
| `CustomerTest` (domain) | 11 | ✅ |
| Application use case tests (6 files) | 23 | ✅ |
| `CustomerCrmFlowIntegrationTest` (real Postgres + real HTTP) | 7 | ✅ |
| **Full repo suite** | **233** | ✅ **0 failures, 0 errors** |

## 5. Design decisions carried through from the plan, confirmed working

- **`Customer` is genuinely independent of `User`**, with an optional `userId` link (mirroring `Specialist`'s own precedent) — proven by the integration test's unlinked-customer scenarios (empty bookings list, zero lifetime value, no error) all passing.
- **Timeline is a read-time merge**, not a physically-written feed — the integration test confirms a note, a tag-add, and a status-change all appear correctly merged without any cross-module write coupling into the Booking module.
- **Lifetime value is computed on every read**, zero staleness risk, confirmed correct in `CalculateCustomerLifetimeValueUseCaseTest` (sums only `COMPLETED` bookings, correctly excludes a still-`PENDING` one).
- **Every endpoint is owner-only** — no customer-facing read path exists anywhere in this module, confirmed by `CustomerController`'s own `get()` method needing an explicit `requireOwner` check (the one pure-read endpoint with no use case underneath doing that check for it - documented in that method's own doc comment as the one path that would otherwise have leaked cross-salon data).
- **Multi-tenant isolation never leaks** — confirmed directly: the integration test creates two salons owned by the same caller, then asserts fetching Salon A's customer through Salon B's path returns 404, not the record.

## 6. Remaining limitations (unchanged from the approved plan, now confirmed still real after implementation)

1. **Walk-in customers still can't have real booking history** - `GetCustomerBookingsUseCase`/`CalculateCustomerLifetimeValueUseCase` both correctly return empty/zero for an unlinked customer, exactly as designed, but this remains gated on a future owner-initiated booking-creation capability (Booking module scope, not touched here per "do not modify Booking Integration").
2. **Identity reconciliation is still unbuilt** - a manually-created `Customer` and a same-phone-number `User` created later via Mobile OTP are two independent rows; `linkToUser()` exists on the domain model and is unit-tested, but nothing calls it yet. No endpoint exposes it in this phase.
3. **`CalculateCustomerLifetimeValueUseCase` is an N+1 query per customer in the list endpoint** (one `BookingRepository` query + up to N `ServiceRepository.findById` calls per customer row) - an accepted Phase 1 simplification per the plan, not revisited here; worth monitoring if a salon's customer list grows large.
4. **Only Specialist/Service-style dedicated endpoints exist for Notes/Tags writes; there is no dedicated "list notes only" endpoint** - notes surface via the merged timeline only. Add one later if a client needs to render notes separately from the full timeline.
5. **A customer's `company` (and phone/email) can't be explicitly cleared via PATCH**, only replaced with a new value - a deliberate merge-only simplification (see `UpdateCustomerUseCase`'s own doc comment), consistent with the "at least one contact method" invariant.
