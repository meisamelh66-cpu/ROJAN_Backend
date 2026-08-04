# ROJAN Customer CRM Integration Preparation Report v1

**Scope:** Resolve the two blockers identified in `ROJAN_Owner_App_Customer_CRM_Integration_Plan_v1.md` §3/§4 before Owner App implementation begins - additive backend read endpoints, and the Owner App activity-duplication fix. This is preparation only; `BackendCustomerRepository` and the DI swap (plan §5, steps 2-6) are not part of this ticket.

---

## 1. Backend: two new read endpoints (additive only)

Added to the existing `CustomerController` (`api/src/main/kotlin/ai/rojan/backend/api/customer/CustomerController.kt`), reusing `CustomerNoteRepository.findByCustomerId`/`CustomerTagRepository.findByCustomerId`, which already existed - no new domain, persistence, or migration work, exactly as the plan anticipated.

| Endpoint | Response | Notes |
|---|---|---|
| `GET /api/v1/salons/{salonId}/customers/{customerId}/notes` | `List<CustomerNoteResponse>` | Sorted oldest-first |
| `GET /api/v1/salons/{salonId}/customers/{customerId}/tags` | `List<CustomerTagResponse>` | Sorted oldest-first, resolves the real ids `DELETE .../tags/{tagId}` needs |

Both endpoints follow the exact same authorization shape as the existing `get()` endpoint (the one other pure-read path with no use case underneath): `findCustomerOrThrow` for tenant isolation (wrong salon → 404) + `requireOwner` for ownership (non-owner → 403). No existing endpoint, DTO, or contract was changed - `CustomerNoteResponse`/`CustomerTagResponse` are the same response types already returned by the `POST .../notes` and `POST .../tags` endpoints.

### Tests added
`bootstrap/src/test/kotlin/ai/rojan/backend/bootstrap/CustomerCrmFlowIntegrationTest.kt` (real embedded Postgres + real HTTP layer), extended rather than duplicated:
- Main lifecycle test: asserts `GET .../notes` returns the added note, `GET .../tags` returns the added tag with its real id.
- Cross-tenant isolation test: asserts `GET .../notes` and `GET .../tags` both return 404 through another salon's path.
- Non-owner test: asserts `GET .../notes` and `GET .../tags` both return 403 for a caller who doesn't own the salon.

## 2. Owner App: activity-duplication fix

`CustomerCommandService` (`src/Rojan.Desktop.Application/Customers/CustomerCommandService.cs`) no longer calls `ICustomerRepository.AddActivityAsync` from any mutation. Per plan §3.3: the backend is now the sole source of truth for the Customer CRM activity timeline.

| Mutation | Previous local log | Why removed |
|---|---|---|
| `CreateCustomerAsync` | "Customer created" | No backend equivalent - a generic create entry doesn't exist server-side |
| `UpdateCustomerAsync` | "Customer profile updated" | Backend's `UpdateCustomerUseCase` logs `STATUS_CHANGED` itself on a real status change; a plain field edit has no backend log at all |
| `AddNoteAsync` | "Note added" | The note itself already appears in the backend's merged timeline as a `NOTE` entry - a separate activity would be redundant |
| `AddTagAsync` | "Tag added: {label}" | Backend's `AddCustomerTagUseCase` logs `TAG_ADDED` itself |
| `RemoveTagAsync` | "Tag removed" | Backend's `RemoveCustomerTagUseCase` logs `TAG_REMOVED` itself |

`ICustomerRepository.AddActivityAsync` itself was left untouched on the interface/implementations (`EfCustomerRepository`, `FakeCustomerRepository`) - only the call sites in `CustomerCommandService` were removed, keeping this change scoped to the Application layer as the plan specified.

### Tests updated
`tests/Rojan.Desktop.Application.Tests/Customers/CustomerCommandServiceTests.cs` - the five tests that previously asserted an activity was logged (`*_LogsCreationActivity`, `*_LogsUpdateActivity`, `*_AddsNoteAndLogsActivity`, `*_AddsTagAndLogsActivity`, `*_RemovesTagAndLogsActivity`) were renamed and inverted to assert `repository.Activities` stays empty. No other test in the solution depended on `CustomerCommandService` performing this logging (confirmed by repo-wide search) - the remaining references to the old description strings are unrelated read-path tests (`CustomerProfileQueryServiceTests`, `CustomerProfileViewModelTests`, etc.) that construct `CustomerActivity` records directly and are unaffected.

## 3. Test results

| Suite | Result |
|---|---|
| Backend (`./gradlew clean build`) | **233/233**, 0 failures, 0 errors |
| Owner App (`dotnet test`) | **2135/2135**, 0 failures |

Owner App breakdown: Domain.Tests 452, Application.Tests 701, Infrastructure.Tests 475, Presentation.Tests 456, ArchitectureTests 6, Shell.Tests 45.

## 4. What this unblocks

Both blockers from `ROJAN_Owner_App_Customer_CRM_Integration_Plan_v1.md` §3.1/§3.2/§3.3 are now resolved. The plan's remaining implementation order (§5, steps 2-6 - `BackendCustomerRepository`, Notes/Tags wiring, Timeline wiring, DI swap, repository tests) has not been started and remains a separate, future ticket.
