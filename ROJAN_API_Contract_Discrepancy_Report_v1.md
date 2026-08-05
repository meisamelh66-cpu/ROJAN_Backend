# ROJAN Backend — API Contract Discrepancy Investigation Report v1

**Priority:** P0
**Mode:** Audit only. No code, contract, or implementation changes made in this pass.
**Trigger:** Mobile Team 2's `ROJAN_Backend_API_Contract_Verification_Report_v1.md` reports **BLOCKED** - "Requested APIs are not present in verified backend commit `8fe9df2`."

---

## Executive Summary

**The root cause is not a missing API. Commit `8fe9df2` does not exist in this repository, in any form.** It is not on the current branch, not on any other branch, not under any tag, not in the reflog, and not among unreachable/dangling objects. The repository has no remote configured to fetch it from either. Whatever Mobile Team 2 verified against, it was not built from a commit that ever existed in `ROJAN_Backend`'s own git history - the hash itself is the discrepancy, not backend functionality.

Independently of that finding, all three requested capabilities were checked directly against current `HEAD` (`6943986`):

| Requested API | Status | Classification |
|---|---|---|
| `POST /api/v1/salons/{salonId}/bookings` | **Exists, exact path match** | Not a discrepancy |
| `GET /api/v1/dashboard/insights` | **Exists, exact path match** | Not a discrepancy |
| `GET /api/v1/customers` / `GET /api/v1/customers/{id}` | **Does not exist at these paths** | **(C) Present with different endpoint paths** - real endpoints are `GET /api/v1/salons/{salonId}/customers` and `GET /api/v1/salons/{salonId}/customers/{customerId}`, and have been salon-scoped since the day Customer CRM was first introduced (never a bare `/api/v1/customers` at any point in history) |

Two independent things are true at once: Mobile Team 2's reference commit is invalid, **and** their Customer endpoint paths (as literally stated in the request) genuinely don't exist - the salon-scoped form does, and always has. Both need to be communicated back to them; fixing only one would leave the investigation half-resolved.

---

## 1. Git State

| | |
|---|---|
| **Branch** | `master` |
| **HEAD commit** | `6943986 8b961fb29de7417a98cdab194e0832e6c` (full hash), `6943986` (short) - "docs: add ROJAN Milestone Backup v3.0 report", committed 2026-08-05 03:51:52 +0330 |
| **Working tree status** | **Not clean** - see note below |
| **Remote(s)** | None configured (`git remote -v` returns empty) |
| **Tags** | `v0.2.0-foundation-complete`, `v0.4.0-backend-production-ready` (neither at or near `8fe9df2`) |
| **Total commits (all branches)** | 16 |

**Working tree disclosure (unrelated to this investigation, noted for completeness since audit rigor requires full disclosure of repository state):** the working tree currently carries uncommitted changes from an in-progress, separate P0 hardening task (Phase 1.1 - Auth API Rate Limiting: modifications to `AuthController.kt`, `GlobalExceptionHandler.kt`, `UseCaseConfig.kt`, the three auth use cases, `RedisRateLimiter.kt`, `SecurityPropertiesConfig.kt`, plus new rate-limiting files and two untracked report `.md` files). **None of these touch `CustomerController`, `DashboardController`, `SalonBookingController`, or any of their DTOs/tests** - confirmed by file path, not assumed. They are irrelevant to the Customer/Dashboard/Booking discrepancy but are disclosed here because they are real, present, uncommitted changes in the tree this investigation ran against.

---

## 2. Investigation of Commit `8fe9df2`

Checked exhaustively, not just a single lookup:

```
$ git cat-file -t 8fe9df2
fatal: Not a valid object name 8fe9df2

$ git log --all --format="%H" | grep -i "8fe9df"
(no output - no match anywhere in reachable history)

$ git fsck --unreachable --dangling
unreachable commit 5c6576b...  "untracked files on master: 6943986 ..."
unreachable commit 2b6d9e8...  "index on master: 6943986 ..."
unreachable commit 276f00f...  "WIP on master: 6943986 ..."
(three dangling commits found - all three are this session's own `git stash` artifacts
 from the unrelated Phase 1.1 work above, none match 8fe9df2)

$ git remote -v
(empty - no remote to fetch a missing object from)
```

**Conclusion: `8fe9df2` is not a valid, reachable, or even unreachable-but-present object in this repository.** Possible explanations, in order of likelihood:
1. A transcription error - a mistyped or truncated hash from a different, correct commit reference.
2. A hash from a different repository entirely (this backend has sibling repos - `ROJAN_Desktop`, `ROJAN_Web`, `ROJAN_DesignLab` - a hash could have been copied from the wrong one).
3. A hash from a commit that existed locally on someone's machine but was never pushed/shared (this repo has no remote at all, so "verified against a commit" implies either a local clone this repository doesn't have access to, or the hash is simply wrong).

This repository cannot resolve which of these occurred - that requires asking Mobile Team 2 directly what they actually built/pulled from.

---

## 3. Per-API Verification Against Current HEAD (`6943986`)

### 3.1 Owner Booking API — `POST /api/v1/salons/{salonId}/bookings`

**Status: EXISTS, exact match.**

| | |
|---|---|
| Controller | `SalonBookingController` |
| File | `api/src/main/kotlin/ai/rojan/backend/api/booking/SalonBookingController.kt` |
| Mapping | `@RequestMapping("/api/v1/salons/{salonId}/bookings")` (line 42), `@PostMapping` (line 73, method `createForCustomer`) |
| Request DTO | `CreateBookingForCustomerRequest` — `api/src/main/kotlin/ai/rojan/backend/api/booking/BookingDtos.kt:33` |
| Response DTO | `BookingResponse` — `api/src/main/kotlin/ai/rojan/backend/api/booking/BookingDtos.kt:60` |
| Use case | `CreateBookingForCustomerUseCase` — `application/src/main/kotlin/ai/rojan/backend/application/customer/CreateBookingForCustomerUseCase.kt` |
| Integration test | `ReceptionBookingFlowIntegrationTest` — `bootstrap/src/test/kotlin/ai/rojan/backend/bootstrap/ReceptionBookingFlowIntegrationTest.kt` — confirmed exercising this exact path (`url("/api/v1/salons/${salon.id}/bookings")`, lines 144/186/209/231/246) |
| First introduced | Controller class in `690711a`/`5e1be8f`; this specific `createForCustomer` endpoint in `e48e0f2` ("ROJAN milestone: Reception booking backend and CRM completed") |

### 3.2 Dashboard — `GET /api/v1/dashboard/insights`

**Status: EXISTS, exact match.**

| | |
|---|---|
| Controller | `DashboardController` |
| File | `api/src/main/kotlin/ai/rojan/backend/api/dashboard/DashboardController.kt` |
| Mapping | `@RequestMapping("/api/v1/dashboard")` (line 21), `@GetMapping("/insights")` (line 28) |
| Response DTO | `DashboardInsightsResponse` — `api/src/main/kotlin/ai/rojan/backend/api/dashboard/DashboardDtos.kt:7` |
| Use case | `GetDashboardInsightsUseCase` — `application/src/main/kotlin/ai/rojan/backend/application/dashboard/GetDashboardInsightsUseCase.kt` |
| Integration test | `DashboardInsightsFlowIntegrationTest` — `bootstrap/src/test/kotlin/ai/rojan/backend/bootstrap/DashboardInsightsFlowIntegrationTest.kt` — confirmed exercising this exact path (lines 70/80/94/116, plus an OpenAPI-docs assertion at line 130) |
| First introduced | `e9a992a` ("ROJAN milestone: Auth OTP, Dashboard, Booking and Customer CRM backend completed") — has never had any other path. |

### 3.3 Customer APIs — `GET /api/v1/customers`, `GET /api/v1/customers/{id}`

**Status: DO NOT EXIST at the requested paths. Classification (C) — present with different endpoint paths.**

The real, existing endpoints are salon-scoped:

| Requested (per Mobile Team 2) | Actual (current HEAD) |
|---|---|
| `GET /api/v1/customers` | `GET /api/v1/salons/{salonId}/customers` |
| `GET /api/v1/customers/{id}` | `GET /api/v1/salons/{salonId}/customers/{customerId}` |

| | |
|---|---|
| Controller | `CustomerController` |
| File | `api/src/main/kotlin/ai/rojan/backend/api/customer/CustomerController.kt` |
| Mapping | `@RequestMapping("/api/v1/salons/{salonId}/customers")` (line 66); list at `@GetMapping` (line 84); single at `@GetMapping("/{customerId}")` (line 110) - plus `/{customerId}/timeline` (119) and `/{customerId}/bookings` (134), not requested but present |
| Response DTO | `CustomerResponse` — `api/src/main/kotlin/ai/rojan/backend/api/customer/CustomerDtos.kt:59` |
| Other DTOs in the same file | `CreateCustomerRequest` (11), `UpdateCustomerRequest` (31), `AddCustomerNoteRequest` (47), `AddCustomerTagRequest` (53), `CustomerTagResponse` (77), `CustomerNoteResponse` (79), `CustomerTimelineEntryResponse` (82) |
| Integration test | `CustomerCrmFlowIntegrationTest` — `bootstrap/src/test/kotlin/ai/rojan/backend/bootstrap/CustomerCrmFlowIntegrationTest.kt` — confirmed exercising the salon-scoped paths throughout (lines 89, 101, 110, etc.) |
| First introduced | `e9a992a`, same commit as Dashboard above. |

**History check (not just current HEAD):** `git log --all -p -- '*CustomerController.kt'` shows exactly one `@RequestMapping` value has ever existed across every commit that touched this file (`e9a992a`, then `56d497b`): `/api/v1/salons/{salonId}/customers`. **A bare `/api/v1/customers` has never existed in this repository's history, at any commit, ever.** This rules out "present in another (older) commit" (category B) for the Customer case specifically - it is not that the path changed at some point; the salon-scoped shape is original and has been constant since Customer CRM's introduction.

---

## 4. Classification Summary (per the audit's own A/B/C framework)

- **(A) Missing completely:** none of the three requested capabilities. Every one has real, working, tested backend functionality.
- **(B) Present in another commit:** none. The salon-scoped Customer paths are not a *different-commit* version of a bare-path API - the bare path never existed at any commit.
- **(C) Present with different endpoint paths:** **Customer APIs only.** Booking and Dashboard match Mobile Team 2's requested paths exactly.

---

## 5. Recommendations (reporting only — no changes made)

1. **Ask Mobile Team 2 for the actual commit/branch/tag they built their verification against.** `8fe9df2` cannot be resolved from this repository under any interpretation - it is either a typo, a hash from a different ROJAN repo, or a commit that was never shared to this repository (no remote exists here to have received it from). Re-running their verification against confirmed `6943986` (or any of the 16 commits enumerated in §1) would immediately resolve two of the three "blocked" items, since Booking and Dashboard already match exactly.
2. **The Customer path discrepancy is real and needs a client-side correction, not a backend fix.** Mobile Team 2's client should call `GET /api/v1/salons/{salonId}/customers` and `GET /api/v1/salons/{salonId}/customers/{customerId}` - the `salonId` path segment is not optional or omittable; every other salon-scoped resource in this API (`services`, `specialists`, `bookings` via `SalonBookingController`) follows the identical convention, so this is consistent existing design, not an inconsistency introduced recently.
3. **No backend work is required** for any of the three reported items - confirmed by direct inspection of controllers, DTOs, use cases, and passing integration tests for all three, at current `HEAD`.

**No code, contract, or implementation changes were made in this investigation.**
