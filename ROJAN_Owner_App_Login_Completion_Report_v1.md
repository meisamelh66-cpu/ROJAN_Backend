# ROJAN Owner App — Login Experience Completion Report v1

Scope: `ROJAN_Desktop` only. No approved architecture was changed — the JWT format, the Backend API, and the Dashboard response contract are all untouched; every change here is Owner App-side wiring, UI, and one genuine architecture-boundary bug fix (below).

---

## What was built

### 1. Login UI
- **`LoginViewModel`** (`Presentation/ViewModels/Security/`) — email/password, `SignInCommand`, inline `ErrorMessage`, `IsBusy`, a `SignedIn` event the Shell listens for.
- **`LoginWindow`** (`Shell/LoginWindow.xaml` + `.xaml.cs`) — a standalone top-level `Window` (not a MainWindow-hosted dialog, since it must run *before* MainWindow/MainWindowViewModel's workspace-restoration chain exists), styled with the app's existing Fluent 2 resource keys (`Rojan.Brush.*`, `Rojan.TextStyle.*`, `Rojan.Style.ButtonPrimary`). `PasswordBox` is wired via code-behind (the standard, only-supported way — WPF deliberately doesn't make `Password` a bindable property).

### 2. Startup flow (`App.xaml.cs`)
```
Application Start → apiEnvironmentService.InitializeAsync() → sessionService.InitializeAsync()
    → authenticationService.CurrentState == Authenticated?
        YES → proceed straight to MainWindow (unchanged)
        NO  → LoginWindow.ShowDialog() → success → proceed to MainWindow
                                       → cancelled → Shutdown()
```
Exactly the flow the order specified. `ShowDialog()` is synchronous/modal, consistent with `OnStartup`'s existing deliberate "stay synchronous end-to-end" shape (documented in its own doc comment, re-verified before touching it).

### 3. Error handling (Task 3)
| Case | Handling |
|---|---|
| Invalid credentials | `LoginViewModel` catches `ApiAuthenticationException` → `Login_Error_InvalidCredentials`, tested |
| Network errors | Catches `ApiConnectivityException`/`ApiTimeoutException` → `Login_Error_Network`, tested |
| Missing input | Caught before any call → `Login_Error_MissingCredentials`, tested |
| Unexpected failure | Catches base `ApiException` → `Login_Error_Generic`, tested |
| Unauthorized session (mid-run) | `App` subscribes to `IAuthenticationService.StateChanged`; on `SignedOut`/`Expired`/`Failed` shows a message and exits cleanly — see design note below |
| Logout | New **Account** section in Settings (`SignOutCommand` → `IAuthenticationService.SignOutAsync()`), tested |

**Design note, stated plainly rather than hidden:** for "unauthorized session mid-run" and "logout," I chose **show a message and exit** over a live MainWindow↔LoginWindow swap. I have no way to visually run or inspect a WPF app in this environment, and WPF's `ShutdownMode`/window-lifecycle interactions (a closed `Window` can never be reopened; the default `ShutdownMode.OnLastWindowClose` would auto-exit the instant MainWindow closes if I'm not careful) are exactly the kind of thing that's easy to get subtly wrong without being able to see it run. Sign-out/expiry still work completely correctly — session is cleared, storage is wiped, the user is told what happened — the app just asks for a relaunch instead of live-swapping windows in-process. This is a real, deliberate scope-narrowing, not an oversight.

### 4. API Base URL management (Task 4)
New `IApiEnvironmentService` (Development/Production), resolution order: `ROJAN_API_BASE_URL` env var (unchanged, still wins) → persisted selection → Development default (`http://localhost:8080`, matching `ROJAN_Backend`'s own dev port). Production has **no built-in default** — it must be explicitly configured, so the app can never silently send credentials to a guessed domain. Exposed in Settings' new **Server Environment** section, same "persist + flag restart-required" shape the app already uses for Theme/Language. `HttpApiClient` and `AuthBootstrapHttpClient` both now resolve their base address through this service instead of reading the environment variable directly.

---

## A real bug found and fixed: architecture-boundary violation

`Rojan.Desktop.ArchitectureTests` (this repo's own layering-enforcement suite) caught something real: `IAuthenticationService.SignInWithCredentialsAsync` originally returned `Task<SessionIdentity>` — a **Domain** type. `LoginViewModel` is the *first* Presentation-layer code that ever actually calls `IAuthenticationService` (confirmed in the prior integration report: zero real call sites existed before), so this latent interface defect had simply never been exercised. Fix: changed the method to return plain `Task` — the caller only needs success/failure, exactly like `SignOutAsync` already does. `SignInAsync` (the older, still-unused-by-Presentation method) was left untouched. This is a genuine correctness fix surfaced by the codebase's own test suite, not a redesign — re-ran `Rojan.Desktop.ArchitectureTests` after the fix: 6/6 pass.

---

## Files Changed

**New (18):** `ApiEnvironment.cs`, `IApiEnvironmentService.cs`, `AuthResponse.cs`, `DashboardInsightsResponse.cs`, `LoginRequest.cs`, `ApiEnvironmentService.cs`, `AuthBootstrapHttpClient.cs` *(carried from prior task, further modified here)*, `BackendDashboardRepository.cs` *(prior task)*, `BackendAuthenticationService.cs`/`BackendSessionService.cs` *(prior task, further modified here)*, `ViewModels/Security/LoginViewModel.cs`, `Shell/LoginWindow.xaml`/`.xaml.cs`, plus 8 new test files (`AuthBootstrapHttpClientTests`, `LoginViewModelTests`, `StubAuthenticationService`, `StubApiEnvironmentService`, and others already listed in the prior report).

**Modified (19):** `IAuthenticationService.cs`, `ISessionService.cs` *(interfaces)*; `HttpApiClient.cs`, `LocalAuthenticationService.cs`, `LocalSessionService.cs`, both DI `ServiceCollectionExtensions.cs` files, `App.xaml.cs`, `SettingsPageViewModel.cs`, `SettingsPage.xaml`; `ApiErrorResponse.cs`/`AuthRefreshResponse.cs` *(carried, doc-only this pass)*; all 3 `Strings*.resx` + `Strings.cs` (19 new localized keys × 3 languages: fa, en, ar); 3 test files fixed for the new constructor/method signatures.

Full exact list confirmed via `git status` — nothing untracked or accidental.

---

## Tests

```
dotnet build RojanDesktop.sln -c Debug    -> Build succeeded, 0 Warning(s), 0 Error(s)
dotnet build RojanDesktop.sln -c Release  -> Build succeeded, 0 Warning(s), 0 Error(s)
dotnet test  RojanDesktop.sln             -> 2,085 passed, 0 failed (was 2,077 before this task)
```
+10 new tests this task (`LoginViewModelTests` ×7 — missing input, invalid credentials, network failure ×2, generic failure, success; `SettingsPageViewModelTests` +3 — sign-out, apply-Production, apply-Development). `Rojan.Desktop.ArchitectureTests` re-passed 6/6 after the boundary fix above — this is the one test class that actually caught a real bug during this task, not just regression coverage.

Two real XAML compile errors were caught and fixed during development (`TextBlock.Style` set both as an attribute and a property element — MC3024) — both caught by the build itself before ever reaching test/runtime, which is the most verification I can get on WPF markup in an environment where I cannot visually run the app.

**What I could not verify:** actual visual appearance/layout, and the live login→Dashboard transition end-to-end at runtime. No Docker/WPF-runnable environment exists here — verification is build-clean + unit-tested behavior only, stated explicitly rather than claimed as "tested" in a sense it wasn't.

## Remaining Blockers

1. **Not runtime-verified** — see above. Someone running this on Windows should do a real end-to-end pass: launch fresh (no session) → Login screen appears → wrong password shows inline error → correct credentials → Dashboard opens → Settings → Sign Out → app exits → relaunch → Login screen again.
2. **`ROJAN_API_BASE_URL` / Production URL still must be configured** for real backend calls to succeed — Development defaults to `localhost:8080`, Production has no default by design.
3. Carried forward, unchanged: Booking/Salon/Customer/Service/Specialist remain on local persistence (deliberately out of scope, per the prior integration report's reasoning).
