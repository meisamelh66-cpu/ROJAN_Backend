# ROJAN E2E Environment Readiness Report v1

Report only — no code was changed, no account was created, nothing was deployed. Where this document says "proposed," that means a spec to execute, not something that already exists.

---

## 1. Backend Runtime

| Check | Status | Detail |
|---|---|---|
| Backend URL | **Not defined** | No fixed production domain exists anywhere in this repo — `DOMAIN_NAME` in `.env.example` is intentionally blank, filled in per-deployment. Dev default is `http://localhost:8080` (matches `application.yml`'s `SERVER_PORT` default and `ROJAN_Desktop`'s `ApiEnvironmentService.DevelopmentUrl`). |
| API availability | ❌ **Unreachable from this environment** | `netstat` confirms nothing listening on port 8080 here. |
| PostgreSQL status | ❌ **Not running** | No Docker, no native Postgres service/process found (`sc query`, `tasklist` both checked). |
| Redis status | ❌ **Not running** | Same — no Docker, nothing listening on 6379. |
| Authentication availability | ⚠️ **Implemented, not reachable** | `POST /api/v1/auth/login`/`/register`/`/refresh` are complete and covered by automated integration tests (real HTTP + embedded Postgres, prior sessions) — that's code-level readiness, not a live, callable endpoint right now. |
| Dashboard API availability | ⚠️ **Implemented, not reachable** | Same distinction — `GET /api/v1/dashboard/insights` is complete and tested, but there is no running instance to call. |

**Bottom line:** the backend is code-ready, not environment-ready. Someone with server access needs to either deploy it (per `DEPLOYMENT.md`) or run Postgres/Redis + the jar locally on a machine that has them, before any of the remaining sections can move from "prepared" to "executed."

---

## 2. Test Account

**Not created — no reachable backend to register it against.** Specified precisely below so it can be created in one step the moment a backend is reachable.

### Proposed account

| Field | Value | Why |
|---|---|---|
| Email | `owner.e2e@rojan.test` | `.test` TLD (RFC 2606 reserved, never resolves) — same convention this repo's own integration tests use for throwaway addresses |
| Password | `RojanE2E!2026` | Meets the backend's only rule (`≥ 8 characters`, `RegisterUserUseCase.MIN_PASSWORD_LENGTH`) |
| Full name | `E2E Test Owner` | |
| Role | `MANAGER` | Matches this repo's own convention for a salon-owning test account (see `AuthenticationFlowIntegrationTest`/`BookingEngineFlowIntegrationTest`'s `registerAndLogin(UserRole.MANAGER)`) — **note:** `role` is informational only, not an authorization gate (confirmed in the earlier architecture audit — any role can own a salon) |

### Exact creation steps (once a backend is reachable)

```bash
# 1. Register
curl -X POST http://<host>/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"owner.e2e@rojan.test","password":"RojanE2E!2026","fullName":"E2E Test Owner","role":"MANAGER"}'
# Expect: 201

# 2. Log in, capture the access token
curl -X POST http://<host>/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"owner.e2e@rojan.test","password":"RojanE2E!2026"}'
# Expect: 200, { "accessToken": "...", ... }

# 3. Create exactly ONE salon owned by this account
curl -X POST http://<host>/api/v1/salons \
  -H "Authorization: Bearer <accessToken>" -H "Content-Type: application/json" \
  -d '{"name":"E2E Test Salon","phone":"+1 555 0100","address":"1 Main St"}'
# Expect: 201
```

### Assigned Salon
**"E2E Test Salon"** (created in step 3 above) — one, deliberately. The dashboard's tenant-resolution rule requires *exactly one* salon per owner (0 → `404 SALON_NOT_FOUND`, 2+ → `409 SALON_CONTEXT_REQUIRED`, both by design, not bugs). Do not create a second salon under this account, or the E2E dashboard step will correctly fail with 409 — that would be a test-setup error, not a product bug.

### Required permissions
None beyond the above — confirmed in the architecture audit: there is no role-based authorization anywhere in this codebase. The only requirement is the ownership relationship established in step 3 (`Salon.ownerId == this account's id`), which is automatic the moment the salon is created by this authenticated account.

### For real, non-completed-booking activity to appear in the dashboard/recommendations (optional, for step 9 below)
The account above alone gives an **empty-state** dashboard (all zeros, empty `services`/`recommendations` — itself a valid, correct thing to verify). If you also want to see populated numbers and at least one AI recommendation (`SERVICE_PERFORMANCE` fires whenever any service has a completed booking), additionally: create a service category, a service, a specialist, working hours, then create + confirm + complete one booking — see `bootstrap/.../BookingEngineFlowIntegrationTest.kt` for the exact call sequence, or `ROJAN_End_To_End_Test_Plan_v1.md`'s step 6 from a prior session.

---

## 3. Owner App Configuration

Verified directly from the current `ROJAN_Desktop` source (`Infrastructure/Api/ApiEnvironmentService.cs`), not assumed:

| Setting | Current behavior |
|---|---|
| API Base URL resolution order | 1) `ROJAN_API_BASE_URL` env var (always wins if set) → 2) persisted Settings selection → 3) Development default |
| Development default | `http://localhost:8080` — no configuration needed, this is already correct for a backend running on the same machine |
| Production default | **None on purpose** — must be set explicitly via Settings → Server Environment → Production → enter a URL → Apply, so the app never silently talks to a guessed domain |
| Where the choice persists | `%LocalAppData%\RojanDesktop\api\environment.json` (plain JSON — not a secret, matches the Theme/Language settings file convention, not the DPAPI-encrypted session file) |
| Takes effect | **On next launch only** (`IsRestartRequired` flag, same pattern as Theme/Language) — changing environment while running does not retarget the already-constructed `HttpClient` |

### Action needed before E2E testing
1. Decide where the backend will actually run (localhost, a VM, a deployed server).
2. If `localhost:8080`: **no Owner App configuration change needed** — Development is the default.
3. If anywhere else: launch the Owner App once, go to Settings → Server Environment → Production → enter the real URL → Apply → **restart the app** before attempting login.

---

## 4. E2E Test Checklist

Exact flow, each stage mapped to the actual implementing code so a tester (or a future automated run) knows precisely what should happen and where to look if it doesn't.

```
Application Start
    ↓
```
`App.xaml.cs.OnStartup`: `apiEnvironmentService.InitializeAsync()` → `sessionService.InitializeAsync()` (attempts to restore a persisted session) → checks `authenticationService.CurrentState`.
**Expected on a fresh install / after Sign Out:** no persisted session → falls through to Login.

```
    ↓
Login
    ↓
```
`LoginWindow` shown via `ShowDialog()`. Enter the test account's email/password → `SignInCommand` → `LoginViewModel.SignInAsync()` → `IAuthenticationService.SignInWithCredentialsAsync(email, password)`.
**Check:** wrong password → inline `"ایمیل یا رمز عبور نامعتبر است."` (`Login_Error_InvalidCredentials`); backend unreachable → `"خطا در اتصال به سرور..."` (`Login_Error_Network`).

```
    ↓
JWT Received
    ↓
```
`BackendAuthenticationService.SignInWithCredentialsAsync` → `AuthBootstrapHttpClient.PostAsync("/api/v1/auth/login", ...)` → real backend `AuthResponse` (`accessToken`, `accessTokenExpiresAt`, `refreshToken`, `refreshTokenExpiresAt`, nested `user`).
**Check:** if you can observe the backend's own access log or add a temporary breakpoint, confirm a real, well-formed JWT string is returned (three base64url segments separated by `.`) — not a locally-generated random-bytes value (that would indicate `LocalAuthenticationService` is still wired instead of `BackendAuthenticationService`, i.e. a DI regression).

```
    ↓
Secure Storage
    ↓
```
`BackendSessionService.CreateSessionFromTokensAsync` → `ISecureStorageService.SetAsync("auth:session", ...)` → DPAPI-encrypted file at `%LocalAppData%\RojanDesktop\security\storage\<sha256-hash-of-key>.dat`.
**Check:** that file exists after login, and that it is **not** human-readable plaintext if opened (confirms the plaintext-storage fix from the prior session actually took effect in the running app, not just in tests).

```
    ↓
Restart Application
    ↓
```
Close the app fully, relaunch.

```
    ↓
Session Restore
    ↓
```
`sessionService.InitializeAsync()` reads the DPAPI file back, checks the refresh token isn't expired, restores `CurrentSession`/`CurrentAccessToken`.
**Check:** the app goes **straight to the Dashboard** — the Login screen must not reappear. If it does, session restoration is broken (check whether the DPAPI file survived, and whether `CurrentUser`'s Windows account is the same one that logged in — DPAPI keys are per-user-account).

```
    ↓
Dashboard Load
    ↓
```
`BackendDashboardRepository.GetKpiMetricsAsync`/`GetRecentActivityAsync` invoked by whatever ViewModel binds `IDashboardRepository` (Dashboard module, registered via `DashboardModule`).

```
    ↓
GET /api/v1/dashboard/insights
    ↓
```
Goes through the normal `IApiClient`/`HttpApiClient` pipeline this time (not `AuthBootstrapHttpClient` — this is an ordinary authenticated call). `Authorization: Bearer <accessToken>` attached automatically.
**Check:** `200` with the full `revenue`/`bookings`/`customers`/`services`/`recommendations` body if the test account owns exactly one salon; `404`/`409` if the salon setup in §2 wasn't followed exactly.

```
    ↓
AI Recommendation Display
```
`BackendDashboardRepository.GetRecentActivityAsync` maps each `recommendations[]` entry to an `ActivityEntry` (see that class's own doc comment for why — no dedicated recommendations UI exists yet, this is the closest existing fit).
**Check:** with the empty-state test account, this list is empty (correct, not a bug). With at least one completed booking (§2's optional step), expect at least a `SERVICE_PERFORMANCE` entry ("پرمخاطب‌ترین خدمت این ماه: ...").

---

## Remaining Blockers

1. **No reachable backend** — the root blocker for everything above. Needs deployment or a local Postgres/Redis + jar run, by someone with the access this sandbox doesn't have.
2. **No test account exists yet** — proposed and scripted in §2, not executed.
3. **No GUI-driving capability in this sandbox** — even once 1 and 2 are resolved, the actual click-through of this checklist needs a human (or a GUI-automation-capable environment) on the Windows machine running `ROJAN_Desktop`. This was the same blocker the prior E2E validation task hit.

## What Would Unblock This

Either:
- Someone runs the checklist above manually and reports back the actual results per stage, or
- This session is given a reachable backend URL and a GUI-automatable environment, at which point the checklist above becomes directly executable rather than documentation.
