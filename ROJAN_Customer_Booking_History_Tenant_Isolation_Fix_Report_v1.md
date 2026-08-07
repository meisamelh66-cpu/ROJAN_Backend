# ROJAN Backend — Customer Booking History Tenant Isolation Fix Report v1

**Mode:** Implementation
**Priority:** P0 Security
**Scope:** Fix tenant isolation before enabling Customer Identity Linking (`ROJAN_Customer_Identity_Linking_Design_v1.md`, §4/§6).
**Repository baseline:** `ROJAN_Backend`, branch `feature/auth-rate-limit-finalization`.

---

## Executive Summary

Fixed the cross-tenant booking-data leak identified during the Customer Identity Linking design: three application-layer use cases (`GetCustomerBookingsUseCase`, `CalculateCustomerLifetimeValueUseCase`, `GetCustomerTimelineUseCase`) resolved a linked customer's bookings via `BookingRepository.findByCustomerId(userId, ...)`, a **platform-wide** query with no salon filter. Once any `Customer` row gets a non-null `userId` (the entire point of the identity-linking feature this fix unblocks), a salon would see that person's booking history, computed lifetime value, and booking timeline events **at every salon they've ever visited**, not just its own.

**Fix:** added one new, additive `BookingRepository` method — `findByCustomerIdAndSalonId` — and switched all three vulnerable call sites to it. The pre-existing, intentionally platform-wide `findByCustomerId` (which correctly powers the customer's own self-service `GET /bookings/mine`) is untouched, both in signature and behavior.

**Result:** 258/258 tests pass (up from 254 — four new tests added, all green), no API contract changes, no existing behavior altered for any currently-shipped endpoint.

---

## 1. Root Cause

A full-repo search for every call site of `BookingRepository.findByCustomerId` (the `UserId`-only overload) found **three** consumers, not just the one flagged in the design doc:

| Call site | File | Consequence if left unfixed |
|---|---|---|
| `GetCustomerBookingsUseCase.execute` | `application/customer/GetCustomerBookingsUseCase.kt:51` (before fix) | `GET /api/v1/salons/{salonId}/customers/{customerId}/bookings` would return a linked customer's bookings at every salon, not just this one — the finding that started this fix. |
| `CalculateCustomerLifetimeValueUseCase.execute` | `application/customer/CalculateCustomerLifetimeValueUseCase.kt:32-33` (before fix) | `CustomerResponse.lifetimeValue` (surfaced on every customer list/detail response) would sum completed-booking revenue from **other salons too**, inflating the number and indirectly signaling to one salon's owner that the customer spends money elsewhere. |
| `GetCustomerTimelineUseCase.execute` | `application/customer/GetCustomerTimelineUseCase.kt:69-71` (before fix) | The merged CRM timeline (`GET .../customers/{customerId}/timeline`) would include `BOOKING_CREATED`/`BOOKING_{STATUS}` entries from bookings made at other salons. |

All three were inert until now only because `customer.userId` has never been non-null in production (no public link mechanism exists yet, per the identity-linking design doc) — this was a dormant bug, not yet an exploited one, but one that would have activated immediately the moment linking shipped.

The self-service endpoint `GET /api/v1/bookings/mine` (`BookingController.mine`) also calls `findByCustomerId(customerId, ...)` — this is **correct, intentional behavior**, not a bug: a customer's own view of "my bookings" should show every salon they've booked at. This call site was deliberately left unchanged.

---

## 2. Changes Made

All changes are additive. No existing method signature, DTO, endpoint mapping, or database column was altered or removed.

### 2.1 Domain layer

**`domain/booking/BookingRepository.kt`** — added `findByCustomerIdAndSalonId`, a new interface method alongside (not replacing) `findByCustomerId`:

```kotlin
/**
 * A customer's bookings at one specific salon only, optionally filtered
 * by status, sorted by start time. Use this, never [findByCustomerId],
 * for any salon-owner-facing view of a specific customer (booking
 * history, lifetime value, timeline) - a linked customer may have
 * bookings at other salons too, and those must never be visible to a
 * salon that isn't theirs.
 */
fun findByCustomerIdAndSalonId(
    customerId: UserId,
    salonId: SalonId,
    pageRequest: PageRequest,
    statusFilter: BookingStatus?,
    sortDirection: SortDirection,
): PageResult<Booking>
```

The existing `findByCustomerId`'s doc comment was updated to explicitly record *why* it stays platform-wide (self-service "my bookings"), so the distinction between the two methods is documented at the point future readers will look, not only in this report.

### 2.2 Infrastructure layer

- **`BookingSpringDataRepository.kt`** — two new Spring Data derived-query methods, following the exact naming convention already used by every other method in this interface: `findByCustomerIdAndSalonId(customerId, salonId, pageable)` and `findByCustomerIdAndSalonIdAndStatus(customerId, salonId, status, pageable)`.
- **`BookingRepositoryAdapter.kt`** — implements the new port method, mirroring `findByCustomerId`'s existing structure exactly (same `pageableSortedByStartTime` helper, same `statusFilter == null` branch pattern).

### 2.3 Application layer — the three vulnerable call sites fixed

| Use case | Before | After |
|---|---|---|
| `GetCustomerBookingsUseCase` | `bookingRepository.findByCustomerId(userId, ...)` | `bookingRepository.findByCustomerIdAndSalonId(userId, customer.salonId, ...)` |
| `CalculateCustomerLifetimeValueUseCase` | `.findByCustomerId(userId, ..., BookingStatus.COMPLETED, ...)` | `.findByCustomerIdAndSalonId(userId, customer.salonId, ..., BookingStatus.COMPLETED, ...)` |
| `GetCustomerTimelineUseCase` | `.findByCustomerId(userId, ...)` | `.findByCustomerIdAndSalonId(userId, customer.salonId, ...)` |

Each use case already had the relevant `Customer` (and therefore its `salonId`) in hand at the point of the call, so no new parameter, command field, or upstream signature change was needed anywhere — the fix is entirely internal to each use case's own body.

### 2.4 Test infrastructure

**`application/test/booking/BookingTestFixtures.kt`** (`InMemoryBookingRepository`) — implements the new interface method (`filter { it.customerId == customerId && it.salonId == salonId }`), required for the fake to keep satisfying the `BookingRepository` interface. This was the only test double implementing `BookingRepository` found in the codebase (confirmed via full-project compilation, §4).

---

## 3. Tests Added

Four new tests, one per affected layer, each asserting the specific leak this fix closes rather than only re-testing the happy path:

| Test | File | What it proves |
|---|---|---|
| `does not leak the linked account's bookings from a different salon` | `application/test/customer/GetCustomerBookingsUseCaseTest.kt` | A customer linked at Salon A, with a second booking at Salon B, sees only the Salon A booking through `GetCustomerBookingsUseCase`. |
| `excludes completed bookings made at a different salon` | `application/test/customer/CalculateCustomerLifetimeValueUseCaseTest.kt` | Lifetime value sums only the requesting salon's completed bookings (`650000`, not `650000 + 2000000` from the other salon). |
| `does not include booking events from a different salon` | `application/test/customer/GetCustomerTimelineUseCaseTest.kt` | The merged timeline contains exactly one `BOOKING_CREATED` entry (the own-salon booking), not two. |
| `does not leak a linked customer's bookings or timeline from a different salon` | `bootstrap/test/CustomerCrmFlowIntegrationTest.kt` | **Full HTTP-level, real-Postgres regression test** — registers a real owner and a real customer account, creates two salons with their own service/specialist, links the customer to a CRM record under Salon A (via the same `customerRepository` bypass `ReceptionBookingFlowIntegrationTest` already established, since no public link endpoint exists yet), books via the real self-service `POST /api/v1/bookings` endpoint at *both* salons, then asserts `GET /api/v1/salons/{salonA}/customers/{id}/bookings` and `.../timeline` return only Salon A's data. |

All four unit-level tests use the existing `InMemoryBookingRepository`/`InMemorySalonRepository` fakes, consistent with every other use-case test in this codebase. The integration test uses the existing `@SpringBootTest(webEnvironment = RANDOM_PORT)` + embedded-Postgres pattern, consistent with every other bootstrap test.

**One bug found and fixed during test-writing, not shipped:** the integration test initially failed with a `400 MALFORMED_REQUEST` — a self-inflicted test bug, not a backend bug. `customerOfA.id` is a `CustomerId` Kotlin value class; string-interpolating it directly (`"${customerOfA.id}"`) rendered as the literal string `CustomerId(value=8303b190-...)` instead of the raw UUID, which the `@PathVariable UUID customerId` binder correctly rejected as malformed. Fixed by interpolating `customerOfA.id.value` instead. Caught immediately by the test itself failing (with a debug print added temporarily to confirm the exact response body, then removed) — worth recording here since it's a plausible mistake for anyone else writing similar tests against this codebase's value-class-heavy domain model.

---

## 4. Verification

```bash
$ ./gradlew clean test --console=plain
BUILD SUCCESSFUL in 2m 12s
26 actionable tasks: 14 executed, 12 from cache
```

Aggregate JUnit results across every module (`domain`, `application`, `infrastructure`, `bootstrap`):

| | Before this fix | After this fix |
|---|---|---|
| Total tests | 254 | **258** |
| Failures | 0 | **0** |
| Errors | 0 | **0** |

**Full-project compilation** (`./gradlew compileKotlin compileTestKotlin`) was also run independently and succeeded across all five modules — confirming `InMemoryBookingRepository` was the only other `BookingRepository` implementer in the codebase and that no other call site was missed (a compile failure would have surfaced immediately if any implementer were left without the new interface method).

---

## 5. Rules Compliance

- **No API contract changes.** No controller, DTO, HTTP path, status code, or request/response shape was touched. `GET /api/v1/salons/{salonId}/customers/{customerId}/bookings`, `.../timeline`, and `CustomerResponse.lifetimeValue` all keep their exact existing shapes — only the *data* they now correctly exclude changed, not the contract.
- **Tests added.** Four new tests across three layers (two use-case unit tests plus one full HTTP/real-Postgres integration test), specifically targeting the leak this fix closes, not just re-asserting existing happy-path behavior.
- **Existing behavior preserved.** `findByCustomerId` (platform-wide) is unchanged in signature and behavior, and `BookingController.mine` (self-service "my bookings," which must remain platform-wide) was deliberately left untouched. All 254 pre-existing tests continue to pass unmodified.

---

## 6. Relationship to Customer Identity Linking

This fix directly unblocks `ROJAN_Customer_Identity_Linking_Design_v1.md`, which explicitly listed closing this gap as a "required before ship, not optional" prerequisite (§4/§6 of that design). With this fix in place, implementing the actual auto-linking mechanism (the new `CustomerRepository.findAllUnlinkedByPhoneNumber` method and the `VerifyOtpUseCase` addition described in that design) can proceed without reactivating the cross-tenant leak this report closes — that implementation work remains a separate, not-yet-started task.

**No API contract changes were made. Tests were added. Existing behavior was preserved.**
