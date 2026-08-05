# ROJAN Reception Booking Backend Implementation Report v1

**Scope:** Phase 0 of `ROJAN_Reception_Booking_Flow_Plan_v1.md` - the one true blocker identified in that audit: no backend endpoint let an owner/reception create a booking on behalf of a customer. Backend only, as approved.
**Status:** Complete. `./gradlew clean build` - **BUILD SUCCESSFUL**, 244/244 tests passing (11 new), 0 failures, 0 errors.
**Constraint compliance:** `BookingController`, `CreateBookingUseCase`, `CreateBookingCommand`, and every other existing Booking use case are **completely untouched** - not one line of the customer self-booking flow changed. Every change is additive: two new files, three edited files (one new import + one new bean + one new endpoint method each).

---

## 1. What was built

```
Owner/Reception (JWT sub, must own the salon)
  -> POST /api/v1/salons/{salonId}/bookings  (NEW - distinct from POST /api/v1/bookings)
  -> Salon.ownerId == callerId                (tenant/authorization check)
  -> Customer.salonId == {salonId in path}    ("customer must belong to the selected salon")
  -> Customer.userId != null                  (must already be linked - see §3)
  -> delegates to the existing CreateBookingUseCase, unmodified
```

### New endpoint
`POST /api/v1/salons/{salonId}/bookings` on the existing `SalonBookingController` (previously GET-only), alongside the customer's own `POST /api/v1/bookings` (unchanged, unreferenced by anything new here).

| | Customer self-booking (existing, untouched) | Owner/Reception booking (new) |
|---|---|---|
| Path | `POST /api/v1/bookings` | `POST /api/v1/salons/{salonId}/bookings` |
| Who it books for | Always the caller's own identity | An explicit `customerId` (CRM `Customer.id`) in the request body |
| Auth | Any authenticated user | Salon owner only |
| Body | `CreateBookingRequest(salonId, serviceId, specialistId, startTime, notes?)` | `CreateBookingForCustomerRequest(customerId, serviceId, specialistId, startTime, notes?)` |

### New files
- `application/src/main/kotlin/ai/rojan/backend/application/customer/CreateBookingForCustomerUseCase.kt` - `CreateBookingForCustomerCommand` + `CreateBookingForCustomerUseCase`. Lives in the `application.customer` package (not `application.booking`) deliberately, mirroring the existing precedent set by `GetCustomerBookingsUseCase`/`GetCustomerTimelineUseCase`/`CalculateCustomerLifetimeValueUseCase`: the Customer module already depends on Booking (never the reverse), keeping `Domain.Booking`/`application.booking` unaware that Customer CRM exists at all.
- `application/src/test/kotlin/ai/rojan/backend/application/customer/CreateBookingForCustomerUseCaseTest.kt` - 5 unit tests, in-memory fakes.
- `bootstrap/src/test/kotlin/ai/rojan/backend/bootstrap/ReceptionBookingFlowIntegrationTest.kt` - 6 tests, real Postgres + real HTTP.

### Modified files (additive only)
- `domain/src/main/kotlin/ai/rojan/backend/domain/common/CustomerDomainExceptions.kt` - added `CustomerNotLinkedToAccountException`.
- `api/src/main/kotlin/ai/rojan/backend/api/common/GlobalExceptionHandler.kt` - new exception added to the existing `handleConflict` handler + `errorCodeFor` mapping (`CUSTOMER_NOT_LINKED_TO_ACCOUNT`, 409). No existing mapping changed.
- `api/src/main/kotlin/ai/rojan/backend/api/booking/BookingDtos.kt` - added `CreateBookingForCustomerRequest`. `CreateBookingRequest`/`BookingResponse`/`RescheduleBookingRequest` untouched.
- `api/src/main/kotlin/ai/rojan/backend/api/booking/SalonBookingController.kt` - added `createForCustomer()` + the `createBookingForCustomerUseCase` constructor dependency. The existing `list()` method is byte-for-byte unchanged.
- `api/src/main/kotlin/ai/rojan/backend/api/config/CustomerUseCaseConfig.kt` - added the `createBookingForCustomerUseCase` bean, wiring in the already-registered `createBookingUseCase` bean from `BookingUseCaseConfig` by type. No existing bean definition changed.

## 2. Design decision: delegate, don't duplicate

`CreateBookingForCustomerUseCase` does not reimplement booking validation or the double-booking conflict check. It resolves the caller's authorization and the target customer's linked account, then calls the **exact same, unmodified** `CreateBookingUseCase.execute(...)` every self-service booking already goes through. Concretely: `CreateBookingUseCase.execute()` has no dependency on *who* is calling - it only takes a `customerId: UserId` and validates salon/service/specialist and reserves the slot atomically via `BookingRepository.reserve()`. The self-service controller (`BookingController`) supplies that `UserId` from the caller's own token; this new use case supplies it by resolving `Customer.userId` instead. Neither path knows about the other.

This was verified directly, not assumed: `CreateBookingForCustomerUseCaseTest`'s last test creates one booking through the new path and proves a second attempt at the same specialist/time throws `BookingConflictException` - the identical exception `CreateBookingUseCase` itself throws, confirming real delegation rather than a parallel reimplementation that could silently diverge.

## 3. Scope decision: linked customers only (stated explicitly, not silently)

`Booking.customerId` is a non-null `UserId` - unchanged by this phase, per the "do not modify the self-booking flow" constraint and the plan's own recommendation not to attempt the invasive `Booking` domain redesign in this pass. Consequently:

- **A customer with a linked account (`Customer.userId != null`)** can be booked through the new endpoint today. This is the primary "existing customer" case in the target flow.
- **A customer with no linked account (a true walk-in)** cannot yet - the endpoint returns `409 CUSTOMER_NOT_LINKED_TO_ACCOUNT` rather than silently misattributing the booking to the owner or crashing. This is the harder case flagged in the plan (§4/§7/§8) as needing its own architecture decision (redesigning what `Booking.customerId` can reference) - explicitly out of scope for Phase 0.

This means Phase 0 alone does not yet make every "Select Existing Customer" choice bookable - only linked ones. It does make the endpoint itself, its authorization, and its tenant isolation fully real and tested, and it gives an honest, distinguishable error (not a generic 500 or a silent misattribution) for the remaining unlinked case, which the Owner App can surface to Reception as "link this customer's account first."

## 4. Tenant isolation

Verified by both the unit and integration tests:
1. **Ownership**: `Salon.findById(salonId)` then `salon.ownerId == callerId`, else `SalonAccessDeniedException` (403) - identical shape to every other owner-only endpoint in this codebase.
2. **Customer-salon membership**: `customerRepository.findById(customerId)?.takeIf { it.salonId == command.salonId }`, else `CustomerNotFoundException` (404) - a customer from a different salon is treated as not found, never leaked, matching `CustomerController`'s own `findCustomerOrThrow` convention exactly.
3. **No new tenant boundary was introduced** - this reuses the same `Salon -> Customer` and `Salon -> Service`/`Salon -> Specialist` scoping every other endpoint already enforces; `CreateBookingUseCase` itself re-validates `service.salonId == salon.id` and `specialist.salonId == salon.id` internally, unchanged.

## 5. Tests

| Suite | Tests | Result |
|---|---|---|
| `CreateBookingForCustomerUseCaseTest` (unit, in-memory fakes) | 5 - happy path, unlinked-customer rejection, non-owner rejection, cross-salon customer rejection, delegated double-booking conflict | ✅ |
| `ReceptionBookingFlowIntegrationTest` (real Postgres + real HTTP) | 6 - happy path (incl. automatic Customer Timeline update), unlinked-customer 409, non-owner 403, cross-salon 404, unauthenticated 401, OpenAPI doc coverage | ✅ |
| Full repo suite | 244 | ✅ **0 failures, 0 errors** (233 baseline + 11 new) |

**Note on the integration test's one deliberate deviation from this codebase's usual pure-HTTP convention:** `ReceptionBookingFlowIntegrationTest` autowires `CustomerRepository` directly for exactly one purpose - creating a *linked* customer for the happy-path test. There is no public HTTP endpoint that links a `Customer` to a `User` yet (confirmed absent in `ROJAN_Customer_CRM_Implementation_Report_v1.md` §6.2 and re-confirmed in the audit); every other bootstrap integration test in this codebase is pure black-box HTTP. This is documented inline in the test file's own doc comment. Every actual assertion in the test still goes through real HTTP.

## 6. Confirms one plan prediction directly

The approved plan's Phase 4 stated the Customer Timeline update "is expected to need zero new code... this step is verification, not implementation." The integration test's happy-path case confirms this directly: after creating a booking through the new endpoint, `GET .../customers/{id}/timeline` immediately shows a `BOOKING_CREATED` entry with no additional write anywhere in this implementation - `GetCustomerTimelineUseCase` (already existing, unmodified) picks it up automatically via `Customer.userId`.

## 7. What is still not done (explicitly out of scope for Phase 0)

Per `ROJAN_Reception_Booking_Flow_Plan_v1.md` §8, unchanged by this phase:
1. **Full walk-in (unlinked) booking support** - needs its own `Booking.customerId` domain decision, deliberately not attempted here (§3 above).
2. **`BackendServiceRepository`/`BackendSpecialistRepository`/`BackendCalendarRepository`** (Owner App side) - Service/Specialist/Calendar selection in the Owner App still read local SQLite data; this phase only unblocks the backend endpoint the Wizard will eventually call.
3. **Wiring the Owner App's `BookingWizardViewModel`/`BookingWorkflowService`** to this new endpoint - a separate, Owner-App-side ticket (plan's Phase 3), not started here.
4. **A distinct Reception/Staff role** - authorization here is still ownership-based (`salon.ownerId == callerId`), same as every other endpoint; "Reception" continues to mean "authenticated as the owner."
