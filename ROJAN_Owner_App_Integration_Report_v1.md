# ROJAN Owner App Integration Report v1

Owner App target: **`ROJAN_Desktop`** (confirmed by user). Everything below is from direct inspection and actual command execution against that repository — `dotnet build`/`dotnet test` were run for real, not inferred from documentation. No source code was changed in either repository to produce this report.

**Important caveat surfaced immediately:** `ROJAN_Desktop/README.md`'s own "Status" section claims *"Phase 01 — Repository & Solution Foundation — Approved. Gate 01 — Development Environment Validation — BLOCKED (install .NET 8 SDK, update VS 2022)... No business code exists yet, by design."* **This is false against the current repository state** — 108 commits exist, a full Clean Architecture solution with 6 source projects, 6 test projects, and 2,051 passing tests exists, and the required .NET 8 SDK is already installed. The README was not used as a source of truth for anything below; every claim here was independently verified against actual source and actual command output.

---

## Application Status

| Field | Value |
|---|---|
| Framework | WPF (.NET 8 desktop UI framework) |
| Technology Stack | .NET 8, C#, WPF, MVVM, Clean Architecture (Domain → Application → Infrastructure/Presentation → Shell) |
| Target framework (verified in `Directory.Build.props`) | `net8.0-windows` |
| Version | Not tagged/versioned in the repo (no `.csproj` `<Version>` or git tag found) — tracked by commit only |
| Latest Commit | `1607de0` — "Sprint 8 Commit 5 - UX Stabilization Sprint 1: approved audit fixes" (2026-07-27) |
| Total commits | 108 |
| Architecture | 6 `src/` projects (`Domain`, `Application`, `Infrastructure`, `Presentation`, `Shell`, `Common`) + matching `tests/` project per layer + a dedicated `Rojan.Desktop.ArchitectureTests` project enforcing layering rules |

### Build Status

**Build command:**
```bash
dotnet build RojanDesktop.sln -c Debug
dotnet build RojanDesktop.sln -c Release
```

**Development Build: PASS** — `dotnet build -c Debug`: `Build succeeded. 0 Warning(s). 0 Error(s).` (32s)

**Production Build: PASS** — `dotnet build -c Release`: `Build succeeded. 0 Warning(s). 0 Error(s).` (29s)

**Last successful build:** this audit, just now (2026-08-04) — both configurations, run directly.

**Test suite:** `dotnet test RojanDesktop.sln`:
```
Rojan.Desktop.Domain.Tests:         452 passed, 0 failed
Rojan.Desktop.ArchitectureTests:      6 passed, 0 failed
Rojan.Desktop.Presentation.Tests:   427 passed, 0 failed
Rojan.Desktop.Shell.Tests:           45 passed, 0 failed
Rojan.Desktop.Application.Tests:    694 passed, 0 failed
Rojan.Desktop.Infrastructure.Tests: 427 passed, 0 failed
-----------------------------------------------------
Total: 2,051 passed, 0 failed
```

### Known Issues

1. **README is severely stale** (see caveat above) — recommend fixing this separately, it actively misleads anyone who reads it first (not fixed here, as this report is audit-only for `ROJAN_Backend`'s side and no code-change approval was given for `ROJAN_Desktop`).
2. **No backend integration exists anywhere in the app today** — every repository interface (`IDashboardRepository`, `IBookingRepository`-equivalent, `ICustomerRepository`-equivalent, etc.) is backed by an in-memory `Fake*Repository` implementation. This is by explicit design at the current phase (doc comments consistently say "no backend integration yet"), not a bug — but it means **zero live wiring exists to audit between the two systems today.**
3. A separate, minimal `Rojan.Server` .NET solution exists nested inside this repo (`ROJAN_Desktop/Rojan.Server/`) with its own `Program.cs`, Docker setup, and a single health-endpoint test. **This is not `ROJAN_Backend`** (the real backend is the Kotlin/Spring Boot service audited throughout this session) — worth Team 1/Team Desktop confirming its purpose isn't confused with the real integration target, but it was not investigated further as it's outside this audit's named scope.

---

## Authentication Audit — Traced

```
Owner App -> Login API -> Identity Validation -> JWT -> Session
```

**Finding: this flow does not exist today.** Traced precisely, stage by stage:

| Stage | Finding |
|---|---|
| **Login API** | **No call to `POST /api/v1/auth/login` exists anywhere in `ROJAN_Desktop`.** `IAuthenticationService.SignInAsync(UserIdentity user)` takes a pre-built `UserIdentity`, not credentials — there is no email/password form, no login screen wired to a backend call. |
| **Identity source today** | `UserIdentity.LocalUser(machineUserName)` — the signed-in **Windows OS account name** is used as the "user," with `Id = "local:{machineUserName}"`. Explicitly documented as a deliberate bridge: *"This app has no multi-account login model yet... Future phases wiring a real backend/IdP replace LocalUser with a server-issued UserIdentity."* |
| **JWT** | **Received: NO.** No JWT is ever requested from or issued by the real backend. `AuthToken.Value` (the desktop's local session token) is documented as *"opaque here by design... this bounded context does not need to parse it, e.g. as a JWT"* — it's `Convert.ToBase64String(RandomNumberGenerator.GetBytes(32))`, i.e., 32 random bytes, not a JWT at all. |
| **Claims (`userId`, `roles`)** | **N/A — nothing to check.** Since no real JWT is ever received, there is no claims-parsing code on the desktop side to audit. |
| **Session** | A `SessionIdentity` is created locally (`LocalSessionService.CreateSessionAsync`) once a `UserIdentity`/`DeviceIdentity` pair exists — this part of the flow works, just entirely offline from the real backend. |

### Token Storage — ⚠️ BLOCKER

**Check performed:** where and how are the session's access/refresh tokens persisted to disk?

**Finding:** `LocalSessionService.Persist()` writes the full session (including both token values) as **plain, unencrypted JSON** via `File.WriteAllText()` to:
```
%LocalAppData%\RojanDesktop\security\auth-session.json
```

This is **plain text on disk**, not secure storage — despite the file living in a folder literally named `security`.

**Why this is marked a blocker, not a lesser finding:** the same codebase already contains a correct, working secure-storage primitive one file away — `DpapiSecureStorageService` (`infrastructure/Security/DpapiSecureStorageService.cs`), which encrypts each value with **Windows DPAPI** (`ProtectedData.Protect`, `DataProtectionScope.CurrentUser` — OS-level, per-user-account encryption, no key management needed) and is already registered in DI (`services.AddSingleton<ISecureStorageService, DpapiSecureStorageService>`) and actively used for other secrets (`SecretProvider`/`LocalKeyProvider`). **`LocalSessionService` simply doesn't use it** — it writes directly to `File.WriteAllText` instead, bypassing the secure primitive that already exists and is already proven to work in this same app.

```
BLOCKER: session/token file (auth-session.json) is plaintext-on-disk.
Fix available in-repo: route it through the existing ISecureStorageService (DPAPI)
instead of File.WriteAllText — no new dependency, no new pattern, the
correct mechanism already exists and is already used elsewhere in this
same codebase.
```

Not fixed in this report — per this task's "report only, no code changes" instruction. Flagged as the single highest-priority item before this app should ever hold a real backend-issued token.

### Refresh Token

**Implemented: YES, but entirely locally.** `ISessionService.RefreshAsync()` rotates the token pair — but its own doc comment states plainly: *"today this rotation happens entirely locally (no backend call)."* The wire contracts for a real backend-backed refresh (`AuthRefreshRequest`/`AuthRefreshResponse`) already exist, deliberately shaped to match `ROJAN_Backend`'s `AuthResponse` fields one-for-one (`accessToken`/`accessTokenExpiresAt`/`refreshToken`/`refreshTokenExpiresAt` ↔ `AccessToken`/`AccessTokenIssuedAt`/`AccessTokenExpiresAt`/`RefreshToken`/`RefreshTokenIssuedAt`/`RefreshTokenExpiresAt`) — prepared for, but not wired to, the real backend.

**Status: Pending.**

---

## Backend API Integration — API Inventory

Cross-referencing `ROJAN_Backend`'s actual endpoints (verified throughout this session) against `ROJAN_Desktop`'s actual consumption (verified this pass — none).

### Auth API
- **Endpoint:** `POST /api/v1/auth/login` (+ `/register`, `/refresh`)
- **Authentication:** None required (public)
- **Backend status:** Implemented, tested, verified (`AuthenticationFlowIntegrationTest`)
- **Owner App consumption:** **Not connected.** No call exists.
- **Response Validation / Error Handling (Owner App side):** N/A — nothing to validate against, since it's never called.

### Dashboard API
- **Endpoint:** `GET /api/v1/dashboard/insights`
- **Authentication:** Bearer JWT required
- **Backend status:** Implemented, tested, verified (this session)
- **Owner App consumption:** **Not connected.** `FakeDashboardRepository` serves 4 hardcoded KPI cards (`KpiMetric`: bookings/clients/revenue/tasks) and a hardcoded activity feed — a **structurally different shape** from the backend's `revenue`/`bookings`/`customers`/`services`/`recommendations` response. This isn't a "flip a config flag" integration; it needs a real mapping layer built.
- **Response Validation / Error Handling:** N/A on the real contract — the fake repository has no error paths (a `Task.Delay` then always-success).

### Booking API
- **Endpoint:** `GET/POST/PATCH /api/v1/bookings*`, `GET /api/v1/salons/{salonId}/bookings`
- **Authentication:** Bearer JWT required
- **Backend status:** Implemented, tested, verified (`BookingEngineFlowIntegrationTest`)
- **Owner App consumption:** **Not connected.** `FakeBookingRepository` (in-memory).

### Salon API
- **Endpoint:** `GET/POST/PUT/DELETE /api/v1/salons*`
- **Authentication:** Bearer JWT required (except public browse)
- **Backend status:** Implemented, tested, verified
- **Owner App consumption:** **Not connected.** No `IOrganizationRepository`/salon-equivalent found calling the real API; `Organizations` module exists in the desktop's domain but wasn't traced further as it wasn't named in this task's explicit scope.

### Customer API
- **Backend status:** No dedicated `/api/v1/customers` endpoint exists in `ROJAN_Backend` — customer identity is the `User`/`UserId` referenced by `Booking.customerId`; there's no separate customer-management API surface.
- **Owner App consumption:** `FakeCustomerRepository` (in-memory) — desktop has its own `Customers` domain module, not connected to the backend's `User` model in any way today.

### AI API
- **Backend status:** No dedicated endpoint — `recommendations` is embedded inside the Dashboard API response (`RuleBasedRecommendationEngine`, rule-based v1, see prior session reports).
- **Owner App consumption:** **Not connected**, and no concept of "recommendation" exists in the desktop's `IDashboardRepository` interface at all today (only `KpiMetric`/`ActivityEntry`) — this would need new desktop-side types, not just a new API call.

### Other
- **Specialist:** Backend has full CRUD + schedule endpoints; Owner App has `FakeSpecialistRepository`, not connected.
- **Services:** Backend has full CRUD; Owner App has `FakeServiceRepository`, not connected.
- **Sync:** `SyncQueueService` (desktop-side) references a `"sync/operations"` path convention, but there is no matching endpoint in `ROJAN_Backend` at all — this is a desktop-side offline-queue concept with no backend counterpart currently.

**Summary: 0 of the audited API surfaces have live Owner App ↔ Backend integration today.** The Owner App's architecture (DI-registered interfaces, a fully-built `HttpApiClient` with connectivity/retry/auth-header/timeout/401-refresh-retry-once handling, `ApiVersion.BasePath()` already matching the backend's `/api/v1/` convention) is **ready to be wired up** — every fake repository is a drop-in-replaceable implementation of an interface that already exists — but none of that wiring has been done yet.

---

## Dashboard Verification (Owner App side)

```
User -> Salon.ownerId -> Dashboard Context -> Metrics
```

This flow is fully implemented and verified on the **backend** (see `ROJAN_Architecture_Compliance_Report_v1.md` and this session's prior dashboard verification report). On the **Owner App** side, the flow does not run at all — the app never reaches the backend.

| Data | Backend / Mock |
|---|---|
| Revenue | **Mock** (`FakeDashboardRepository`'s static `kpi-revenue` card) |
| Bookings | **Mock** (`FakeDashboardRepository`'s static `kpi-bookings` card) |
| Customers | **Mock** (`FakeDashboardRepository`'s static `kpi-clients` card) |
| Services | **Mock** — no per-service breakdown concept exists in the desktop's dashboard model at all |
| AI Insights | **Mock** — no recommendation concept exists in the desktop's dashboard model at all |

---

## Ecosystem Integration Audit

### A) Customer Booking → Backend → Owner App
**Status: FAIL.** Backend booking creation/lifecycle is real and tested. The Owner App has no live connection to it (`FakeBookingRepository`) — a booking created via the real API today would never appear in the Owner App.

### B) Website Booking → Booking API → Backend → Owner Dashboard
**Status: FAIL.** Same root cause — the Backend and Booking API legs are real and tested (per this session's work), but the final "→ Owner Dashboard" leg is broken because the Owner App's dashboard is mock data (see above), regardless of what the Website does upstream.

### C) Specialist Schedule → Backend → Owner App → Specialist
**Status: FAIL.** Backend specialist/schedule endpoints are real and tested. Owner App uses `FakeSpecialistRepository` — no live connection.

**Pattern across all three:** every failure has the identical root cause — **the Owner App has no live backend connection anywhere yet.** This is not three separate integration bugs; it's one integration gap (no `ROJAN_API_BASE_URL` configured, no login wired, every repository still faked) manifesting three times.
