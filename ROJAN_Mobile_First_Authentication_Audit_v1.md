# ROJAN Mobile-First Authentication Audit v1

Audit only — no code changed. This documents what exists today and what a Mobile Number + OTP flow would require; it does not implement anything. Waiting for approval before any coding, per instruction.

**Note on timing:** this audit interrupted an in-progress real E2E validation of the current email/password login (against a live backend, mid-way through a real UI-automation-driven login attempt). That validation was stopped cleanly — no credentials were submitted, the app window was closed, nothing was left in an inconsistent state. Findings from the portion that did complete are folded in below where relevant (e.g., confirmed real JWT claim shape from a live-decoded token).

---

## 1. Current State

**Authentication model end-to-end today: email + password, full stop.** Verified by reading the actual schema, domain model, and every layer between them — not assumed.

```
Owner App (LoginWindow: Email + Password fields)
        ↓
IAuthenticationService.SignInWithCredentialsAsync(email, password)
        ↓
POST /api/v1/auth/login  { "email": "...", "password": "..." }
        ↓
AuthenticateUserUseCase: UserRepository.findByEmail(Email) → BCrypt.matches(password, user.passwordHash)
        ↓
JWT issued: claims { sub=userId, email, role, type, iss, iat, exp }  ← no phone claim, no OTP concept
        ↓
Owner App: DPAPI-encrypted secure storage
        ↓
Dashboard (salon resolved from JWT's userId, unrelated to how the user authenticated)
```

There is **no mobile number field anywhere** in either codebase's identity model — not on the backend `User` entity, not in the `users` database table, not in the Owner App's `UserIdentity`. Confirmed by direct source/schema inspection and a codebase-wide search for `phone`/`otp`/`sms`/`mobile` in both repos: every match in `ROJAN_Backend` is `Salon`/`Branch`'s own business contact phone field (unrelated to auth); every match in `ROJAN_Desktop` is a `Customer`/`Specialist`/`Employee`/`Supplier` contact-info field (also unrelated to auth, and part of the local-persistence domains explicitly out of scope per earlier sessions). **Zero OTP/SMS infrastructure exists in either codebase.** This is a from-scratch build, not a modification of something partial.

---

## 2. Files Involved

### `ROJAN_Backend`
| File | Role | Mobile-first impact |
|---|---|---|
| `domain/user/User.kt` | `User` aggregate: `email: Email` (non-nullable), `passwordHash`, `fullName`, `role` | Needs a phone field; email's nullability is the central design question (§6) |
| `domain/user/UserRepository.kt` | `findByEmail`/`existsByEmail` only | Needs `findByPhoneNumber`/`existsByPhoneNumber` |
| `infrastructure/.../db/migration/V1__init_schema.sql` | `users.email VARCHAR(255) NOT NULL`, `UNIQUE INDEX uq_users_email` | New migration needed: `phone_number` column + index; `email` may need to become nullable |
| `application/auth/RegisterUserUseCase.kt`, `AuthenticateUserUseCase.kt` | Register/login, both keyed on email+password | Needs OTP-request/verify use case equivalents, not a modification of these |
| `application/auth/RefreshTokenUseCase.kt` | Unaffected — operates on the refresh token itself, not credentials | No change needed |
| `infrastructure/security/JwtTokenProvider.kt` | Claims: `sub`, `email`, `role`, `type`, `iss`, `iat`, `exp` | `email` claim would need to become optional, or a `phone` claim added alongside |
| `infrastructure/security/RojanUserDetailsService.kt` | Spring Security's `loadUserByUsername(username)` — `username` **is** the email today | Needs to resolve by phone if phone becomes the primary/only identifier |
| `api/common/CurrentUserResolver.kt` | Resolves JWT → `UserId` via `userRepository.findByEmail(Email(principal.username))` | Same dependency — breaks if a user has no email |
| `api/auth/AuthController.kt`, `AuthDtos.kt` | `POST /login` takes `{email, password}` | New endpoints needed, not a modification (§4) |
| `SecurityConfig.kt` | `PUBLIC_ENDPOINTS` includes `/api/v1/auth/**` | New OTP endpoints would need adding to this list — the one place a code change is small and low-risk |

### `ROJAN_Desktop`
| File | Role | Mobile-first impact |
|---|---|---|
| `Presentation/ViewModels/Security/LoginViewModel.cs` | `Email`, `Password`, single-step `SignInCommand` | Needs a two-step (phone → OTP) state machine, not a field rename |
| `Shell/LoginWindow.xaml`/`.xaml.cs` | Single screen: Email TextBox + PasswordBox + Sign In button | Needs a second screen/state for OTP entry, phone-number formatting/validation, resend-countdown UX |
| `Application/Security/IAuthenticationService.cs` | `SignInWithCredentialsAsync(email, password)` | Wrong shape for OTP (single call, not request→verify) — needs new methods, not a parameter change |
| `Infrastructure/Security/BackendAuthenticationService.cs` | Calls `POST /api/v1/auth/login` | Needs new calls to whatever OTP endpoints the backend adds |
| `Application/Api/Contracts/LoginRequest.cs`, `AuthResponse.cs` | Mirror the backend's email+password shape exactly | New contracts needed alongside these, not instead of (backward-compat question, §5) |
| `Domain/Identity/UserIdentity.cs` | `record UserIdentity(string Id, string DisplayName, string? Email)` | `Email` is already nullable here (interesting - the desktop's own type doesn't force it) — would need a `PhoneNumber` field alongside |
| `ISessionService`/`BackendSessionService`, DPAPI storage | Operates on tokens, not credentials | **Unaffected** — session/token lifecycle is identical regardless of how the token was obtained |

**Important, low-risk finding:** everything *after* JWT issuance — session creation, DPAPI-secured persistence, refresh, `HttpApiClient`'s auth-header attachment, the 401-retry-once logic, the Dashboard integration — is **completely decoupled from how the user logged in**. A JWT is a JWT once issued. This significantly narrows the actual blast radius: the OTP work is confined to the login *entry point* on both sides, not a redesign of session management.

---

## 3. Backend Changes Required

All genuinely new, not modifications of existing auth code (existing email/password code would stay as-is unless a decision is made to remove it — see §6):

1. **Domain:** a `PhoneNumber` value class (format validation — Iran mobile numbers, given this app's Persian-first UI, e.g. `09XXXXXXXXX`/`+98...`), added to `User`. Decide: does `email` become nullable, or does every user still need one (dual-identifier)?
2. **Database:** new migration — `phone_number VARCHAR NOT NULL/NULL UNIQUE`, possibly `email` relaxed to nullable. A **breaking schema change** if email becomes optional (existing rows all have email today, so this direction is low-risk migration-wise; the risky direction is deprecating email later).
3. **OTP lifecycle infrastructure — entirely new:**
   - Generation (typically 4-6 digit numeric, short TTL — 2-5 minutes is conventional).
   - Storage with expiry — **Redis is already wired into this stack and currently unused** (confirmed in every prior audit this session: "prepared, no feature consumes it yet"). OTP storage is the natural first real consumer of it.
   - **SMS delivery — a genuinely new external dependency.** No SMS gateway/provider integration exists anywhere in this codebase. This means a new account/contract with an SMS provider (for Iran specifically, likely a local provider like Kavenegar/Ghasedak/Melipayamak rather than Twilio, given deliverability), new outbound network dependency, new cost line, new failure mode (provider downtime, deliverability, spam-filtering) that doesn't exist today.
4. **New endpoints**, e.g. `POST /api/v1/auth/otp/request` (phone → triggers SMS, always returns success-shaped response regardless of whether the phone is registered, to avoid enumerating valid accounts) and `POST /api/v1/auth/otp/verify` (phone + code → `AuthResponse`, same shape as today's login response so the Owner App's *post-auth* handling needs zero changes).
5. **`RojanUserDetailsService`/`CurrentUserResolver`**: resolve by phone instead of (or alongside) email.
6. **Rate limiting — this is not optional here.** Already a documented, standing gap ("no rate limiting exists anywhere," carried in the security backlog since the architecture audit). An OTP-request endpoint with zero rate limiting is a direct SMS-bombing/cost-abuse vector against a real paid SMS provider — this graduates from "should fix eventually" to "must fix before this ships," specifically for the new endpoint.

---

## 4. Owner App Changes Required

1. **`LoginViewModel` redesign** (not a rename): two-stage state — `PhoneNumber` entry → `RequestOtpCommand` → OTP entry stage → `VerifyOtpCommand`. Needs new state (`CurrentStage`, resend-countdown, "wrong number, go back" affordance).
2. **`LoginWindow.xaml`**: either two Views swapped via a `ContentControl`/`DataTemplate` keyed on stage, or two separate Windows shown in sequence (mirroring how `LoginWindow` itself is a separate top-level Window from `MainWindow` today, for the same "must work before the rest of the app's DI graph is fully needed" reason).
3. **`IAuthenticationService`**: add `RequestOtpAsync(phoneNumber)` / `VerifyOtpAsync(phoneNumber, code)`. Same "additive, don't break the existing interface" approach used for `SignInWithCredentialsAsync` two sessions ago — `SignInAsync`/`SignInWithCredentialsAsync` can stay untouched if email/password isn't being removed (§6 decision).
4. **New contracts** (`OtpRequestRequest`, `OtpVerifyRequest`) mirroring whatever shape the backend settles on.
5. **Phone number input/validation UX**: country-code handling, format-as-you-type, resend timer, "didn't receive it" affordance — real UX work, not just wiring.
6. **Localization**: new `Strings.*` keys (phone label, OTP label, resend, countdown, invalid-code error, expired-code error) across all three locale files, same pattern as the 19 keys added for the current login screen.

---

## 5. Migration Risks

1. **The existing E2E test account and this session's own login/dashboard validation work are email+password-based.** If email/password is removed rather than supplemented, `owner.e2e@rojan.test` (created and verified live against a real backend instance two tasks ago) becomes unusable without a phone number, and every integration test written this session (`AuthenticationFlowIntegrationTest`, `DashboardInsightsFlowIntegrationTest`, etc.) that registers via email/password would need rewriting, not just re-running.
2. **`IAuthenticationService.SignInWithCredentialsAsync`'s shape doesn't fit OTP at all** — it's a single request/response call; OTP is inherently two round-trips (request, then verify) with state held between them (the phone number, and a resend timer) that the current interface has nowhere to put. This is a genuine interface redesign for the new methods, not an extension of the old one.
3. **New external dependency (SMS provider)** introduces a failure mode and cost model that doesn't exist today — deliverability failures, provider outages, and per-message cost all become part of the login critical path in a way password auth never was.
4. **Rate limiting is a hard prerequisite, not a nice-to-have**, specifically for `otp/request` — shipping this without it turns a real cost center (SMS) into an open abuse vector. This depends on closing a gap that's been in the backlog since the very first architecture audit.
5. **JWT claim shape change**: if `email` becomes optional on `User`, every place that currently assumes `claims["email"]` is always present (`CurrentUserResolver`, `RojanUserDetailsService`) needs to switch to a claim that's always present regardless of login method — likely `sub` (already always the userId) rather than `email`, which is a small but real change to two security-critical files.
6. **No backward-compatibility story decided yet** — do existing email/password accounts keep working indefinitely, get a mandatory one-time phone-linking step, or does email/password get removed outright? This is a product decision (§6/§7), not something this audit resolves.

---

## 6. Recommended Implementation Plan

Presented as options with a recommendation, not a decision — this is exactly the kind of call that should come from you, not be assumed:

**Phase 1 — Backend, additive (recommended starting point):**
- Add `phone_number` (nullable, unique-when-present) to `users` — email stays `NOT NULL` for now, nobody's existing account breaks.
- Build OTP request/verify endpoints as genuinely new, alongside `/auth/login`/`/auth/register` — both auth methods live side by side.
- Wire Redis for OTP storage (its first real use).
- Rate-limit `otp/request` before this goes anywhere near a real SMS bill.
- Decide the JWT claim question now (recommend: stop relying on `email` claim being present anywhere; use `sub` for identity resolution, keep `email` claim optional) — cheap to do now, expensive to do after clients depend on the old assumption.

**Phase 2 — Owner App, additive:**
- New phone/OTP login path alongside the existing screen (e.g., a toggle/tab: "Email" vs "Phone"), not a replacement — lets this ship and get real usage data before anyone commits to removing email/password.
- `IAuthenticationService` gains the new methods; nothing existing is deleted.

**Phase 3 — Decide the endgame (a later, separate approval point):**
- Once phone/OTP is live and used, decide: keep both permanently (many consumer apps do — email/password for some contexts, phone/OTP as the primary "fast" path), force-migrate existing accounts to phone, or somewhere in between. **Not a decision to make now, and not one this audit is making** — flagging it explicitly so it doesn't get decided by default via whatever's easiest to code later.

**What I would need from you before Phase 1 starts:** confirmation of this phased (additive-first) approach vs. a hard cutover, and which SMS provider/account to integrate against (a real, paid, external dependency — not something to pick unilaterally).
