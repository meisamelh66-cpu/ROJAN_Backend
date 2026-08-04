# ROJAN Owner App — Backend Integration Report v1

Scope: `ROJAN_Desktop` only. Delivers real backend integration for **Authentication** and **Dashboard** (Tasks 1, 3, 4 in full); **Task 2's API client layer** is delivered for Auth + Dashboard, with Booking/Salon/Customer/Service/Specialist explicitly deferred — reasoning below, this was surfaced *before* coding per the order's instruction, not discovered after the fact.

---

## Scope decision, restated

Deep inspection (done before writing any code) found that `Booking`/`Customer`/`Specialist`/`Service` are **not** on `Fake*` repositories — Sprint 6 already migrated them to real **local** EF Core/SQLite persistence. Swapping these to backend-API sync means deciding a source-of-truth/conflict-resolution strategy nobody specified — exactly the kind of "redesign architecture" this order forbade me from doing unilaterally. `Salon` maps to the desktop's much richer `Organizations` model (branches, permission engine, subscription plans) with no backend equivalent for most of it. "Customer API" doesn't exist as a distinct backend endpoint at all. All four are left untouched, with their existing local persistence intact.

**Delivered in full:** Auth (real login + real refresh + DPAPI-secured storage) and Dashboard (real `GET /api/v1/dashboard/insights`) — the two pieces that were genuinely still fake and are the P0-critical core of the owner experience.

---

## Files Changed

### New (11)
| File | Purpose |
|---|---|
| `Application/Api/Contracts/LoginRequest.cs` | Matches backend `LoginRequest` |
| `Application/Api/Contracts/AuthResponse.cs` | Matches backend `AuthResponse` (+ nested `AuthUserResponse`) |
| `Application/Api/Contracts/DashboardInsightsResponse.cs` | Matches backend's dashboard response, field-for-field |
| `Infrastructure/Api/AuthBootstrapHttpClient.cs` | Standalone HTTP client for login/refresh only — see below for why |
| `Infrastructure/Security/BackendSessionService.cs` | Real `ISessionService` — DPAPI-secured, backend-issued tokens |
| `Infrastructure/Security/BackendAuthenticationService.cs` | Real `IAuthenticationService` — calls the backend login API |
| `Infrastructure/Dashboard/BackendDashboardRepository.cs` | Real `IDashboardRepository` — calls the backend dashboard API |
| `Infrastructure.Tests/Api/AuthBootstrapHttpClientTests.cs` | 6 tests |
| `Infrastructure.Tests/Security/BackendSessionServiceTests.cs` | 7 tests |
| `Infrastructure.Tests/Security/BackendAuthenticationServiceTests.cs` | 4 tests |
| `Infrastructure.Tests/Dashboard/BackendDashboardRepositoryTests.cs` | 6 tests |

### Modified (9)
| File | Change |
|---|---|
| `Application/Security/ISessionService.cs` | Added `CreateSessionFromTokensAsync` (additive — existing method/signature untouched) |
| `Application/Security/IAuthenticationService.cs` | Added `SignInWithCredentialsAsync` (additive) |
| `Application/Api/Contracts/ApiErrorResponse.cs` | Field names corrected to match the real backend's `ApiError` (was `Code`/`Details`, invented before a backend existed; now `ErrorCode`/`Status`/`Error`/`Path`/`TraceId`) |
| `Application/Api/Contracts/AuthRefreshResponse.cs` | Doc comment marks it superseded by `AuthResponse` (the backend returns the identical shape from both `/login` and `/refresh`) — left in place, unreferenced, not deleted |
| `Infrastructure/Security/LocalSessionService.cs` | Implements the new interface method (locally-generated tokens, for backward compatibility) — stays registered nowhere now, unreferenced |
| `Infrastructure/Security/LocalAuthenticationService.cs` | Implements the new interface method (throws — no backend to authenticate against) |
| `Infrastructure/DependencyInjection/ServiceCollectionExtensions.cs` | Swapped `ISessionService`/`IAuthenticationService`/`IDashboardRepository` registrations to the new `Backend*` implementations; registered `AuthBootstrapHttpClient` |
| `Application.Tests/Api/Contracts/ApiErrorResponseTests.cs` | Updated for the corrected `ApiErrorResponse` shape |
| `Infrastructure.Tests/Api/HttpApiClientTests.cs` | `StubSessionService` implements the new interface method (compile fix) |

`LocalSessionService`, `LocalAuthenticationService`, and `FakeDashboardRepository` all stay in the codebase, unreferenced — matching this repo's own established Sprint 6 convention for a superseded-but-still-useful implementation, not deleted.

---

## APIs Connected

| API | Endpoint | Real? |
|---|---|---|
| Auth (login) | `POST /api/v1/auth/login` | ✅ |
| Auth (refresh) | `POST /api/v1/auth/refresh` | ✅ |
| Dashboard | `GET /api/v1/dashboard/insights` | ✅ |
| Booking, Salon, Customer, Service, Specialist | — | ❌ Deferred (see scope decision) |

### A real design finding: login/refresh had to bypass the normal API client

`HttpApiClient` (the app's one shared HTTP pipeline) already auto-retries a 401 by calling `ISessionService.RefreshAsync()`. If the real `/auth/refresh` call had gone through that same pipeline, a rejected refresh (expired/invalid refresh token, itself a 401) would re-enter `RefreshAsync()` from inside its own in-flight call — unbounded recursion. The same problem applies to a login attempt with the wrong password. Fix: a new, deliberately minimal `AuthBootstrapHttpClient` handles only these two calls, with none of `HttpApiClient`'s auto-refresh logic (there's no token to attach yet for either call anyway). This is a small, additive component, not a change to the existing shared pipeline — `HttpApiClient` itself was not modified at all.

---

## Token Lifecycle (as implemented)

1. **Login** — `BackendAuthenticationService.SignInWithCredentialsAsync(email, password)` → `AuthBootstrapHttpClient.PostAsync("/api/v1/auth/login", ...)` → backend returns `{ user, accessToken, accessTokenExpiresAt, refreshToken, refreshTokenExpiresAt }`.
2. **Session creation** — the response is handed to `ISessionService.CreateSessionFromTokensAsync(...)`. `SessionIdentity.ExpiresAt` is set from the **refresh token's** expiry (the session's true outer bound — matches the pattern `LocalSessionService` already established).
3. **Persistence** — `BackendSessionService` writes the session (+ both tokens) as one JSON blob through `ISecureStorageService.SetAsync("auth:session", ...)` — the existing, already-proven **DPAPI**-backed service (`DpapiSecureStorageService`, Windows `ProtectedData`, per-user-account encryption). This is the fix for the audit's plaintext-storage blocker: no `File.WriteAllText` anywhere in this new code path.
4. **Every subsequent request** — `HttpApiClient.AttachAuthenticationHeader` reads `ISessionService.CurrentAccessToken` fresh on every call and attaches it as `Authorization: Bearer <token>` if not expired. Unchanged, pre-existing behavior — the real token now flows through the exact same seam the fake one used to.
5. **Access token expiry mid-session (15 min, backend default)** — a request gets `401`. `HttpApiClient.EnsureAuthenticatedAsync` calls `ISessionService.RefreshAsync()` **exactly once**, retries the original request once with the new token, and only throws `ApiAuthenticationException` if that retry also fails. Unchanged, pre-existing retry logic — now backed by a real refresh instead of locally-generated bytes.
6. **Refresh** — `BackendSessionService.RefreshAsync()` calls `AuthBootstrapHttpClient.PostAsync("/api/v1/auth/refresh", { refreshToken })` (bypassing `HttpApiClient` — see above), receives a fresh token pair, re-persists via DPAPI, updates `SessionIdentity.ExpiresAt` from the new refresh token's expiry.
7. **App restart** — `sessionService.InitializeAsync()` (already called at Shell startup, unchanged) reads the DPAPI-encrypted blob back, restores the session if the refresh token isn't expired, else discards it.
8. **Refresh token expiry (30 days, backend default)** — `RefreshAsync()` throws `InvalidOperationException`; `HttpApiClient` maps this to `ApiAuthenticationException("Session refresh failed after an authentication failure - sign in again.")`. There is no automatic re-login — the caller must call `SignInWithCredentialsAsync` again.
9. **Logout** — `SignOutAsync()` → `ISessionService.ExpireAsync()` → clears in-memory state and calls `ISecureStorageService.RemoveAsync("auth:session")`, deleting the DPAPI-encrypted file.

---

## Session Management (Task 4) — verified, not rebuilt

| Requirement | Mechanism |
|---|---|
| Login state | `IAuthenticationService.CurrentState`/`StateChanged` — pre-existing, now backed by real state transitions |
| Logout | `SignOutAsync()` → `ExpireAsync()` — pre-existing method, now clears DPAPI storage instead of a plaintext file |
| Token loading (startup) | `ISessionService.InitializeAsync()`, already called in `App.xaml.cs` at startup — pre-existing call site, now reads real DPAPI-encrypted tokens |
| Unauthorized handling | `HttpApiClient`'s existing 401-refresh-and-retry-once logic — pre-existing, untouched, now exercised by a real refresh call |

Nothing here needed new plumbing — the app's session architecture was already correctly shaped for this; only the concrete implementations behind the interfaces were fake.

---

## Tests

```
dotnet build RojanDesktop.sln -c Debug    -> Build succeeded, 0 Warning(s), 0 Error(s)
dotnet build RojanDesktop.sln -c Release  -> Build succeeded, 0 Warning(s), 0 Error(s)
dotnet test  RojanDesktop.sln             -> 2,075 passed, 0 failed (was 2,051 before this task)
```
Breakdown of the +24 new/changed: 23 new tests (`AuthBootstrapHttpClientTests` ×6, `BackendSessionServiceTests` ×7, `BackendAuthenticationServiceTests` ×4, `BackendDashboardRepositoryTests` ×6) + 1 net from rewriting `ApiErrorResponseTests` (3→4). `Rojan.Desktop.ArchitectureTests` (layering-rule enforcement) passed 6/6 — the new classes don't violate Clean Architecture boundaries. Zero existing tests were modified in behavior, only `HttpApiClientTests`' `StubSessionService` gained a required interface-method stub (compile fix, not a behavior change).

`git status` confirms exactly the 20 files listed above changed — no stray build artifacts, no other files touched.

---

## Remaining Blockers

1. **No WPF login screen exists.** `BackendAuthenticationService.SignInWithCredentialsAsync` is fully implemented and tested, but nothing in the Presentation/Shell layer calls it yet — there is no View/ViewModel collecting email/password. This is a UI task, deliberately out of this "backend integration" order's scope (the task's own flow diagrams are service-layer, not UI).
2. **Booking/Salon/Customer/Service/Specialist remain on local persistence / fake data**, per the scope decision above — needs a source-of-truth/sync-strategy decision before real work can start there.
3. **`ROJAN_API_BASE_URL` must be set** for any of this to actually reach a server — not set by this task (environment configuration, not code).
4. **Recommendations are mapped into the existing "recent activity" feed**, not a dedicated UI concept — functional (real AI insights now reach the app), but a future dedicated recommendations UI would read better than borrowing the activity-feed shape.
5. Carried forward from the prior audit, still open: `ROJAN_Desktop/README.md` is still stale; `Rojan.Server` (the unrelated nested .NET scaffold) still risks being confused with the real backend.
