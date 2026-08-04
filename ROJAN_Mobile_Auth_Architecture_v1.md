# ROJAN Mobile-First Authentication Architecture v1

Proposal only — no code written. Builds directly on `ROJAN_Mobile_First_Authentication_Audit_v1.md`'s findings (referenced inline rather than repeated). Where this document makes a judgment call, it says so explicitly; where it needs your sign-off, it says that too — this is a proposal to approve or amend, not a fait accompli.

---

## 1. Backend OTP API Design

All three endpoints under the existing `/api/v1/auth/**` prefix (already public in `SecurityConfig.PUBLIC_ENDPOINTS` — no security-boundary change needed, just three new entries reachable by that existing wildcard).

Phone numbers throughout: **E.164 format** (`+989123456789`) — one unambiguous wire format, validated at the API boundary, consistent with how this codebase already validates `Email` as a domain value type (`domain/user/Email.kt`'s regex-validated value class) — a `PhoneNumber` value class follows the identical pattern.

### `POST /api/v1/auth/otp/request`
```json
// Request
{ "phoneNumber": "+989123456789" }

// Response — 202 Accepted (an SMS side-effect is in flight, not a completed synchronous result)
{ "phoneNumber": "+989123456789", "expiresInSeconds": 120, "canResendAfterSeconds": 60 }
```
**Deliberately identical response whether or not the phone is already registered** — no enumeration signal. First-time verification of a previously-unseen phone number auto-registers the account (see §4/§5 — this is the "OTP unifies register+login" pattern most mobile-first apps use, matching what a phone-first product actually feels like to a user; flagged here as a judgment call, not assumed silently).

Errors: `400` malformed phone number (`errorCode: INVALID_PHONE_NUMBER`) · `429` rate-limited (`errorCode: OTP_RATE_LIMITED`, `Retry-After` header) — see §2.

### `POST /api/v1/auth/otp/verify`
```json
// Request
{ "phoneNumber": "+989123456789", "code": "482913" }

// Response — 200 OK, EXACT SAME SHAPE as today's /auth/login response
{
  "user": { "id": "...", "phoneNumber": "+989123456789", "fullName": "...", "role": "..." },
  "accessToken": "...", "accessTokenExpiresAt": "...",
  "refreshToken": "...", "refreshTokenExpiresAt": "..."
}
```
**This shape reuse is the single most important design decision in this proposal.** Reusing `AuthResponse` byte-for-byte means the Owner App's entire post-authentication chain (session creation, DPAPI storage, dashboard) needs zero changes — see §4.

Errors: `401` wrong/expired code (`errorCode: OTP_INVALID` — deliberately one code for both "wrong" and "expired" and "never requested," not three, so a caller can't distinguish "this code doesn't exist" from "you got it wrong," which would itself be a minor enumeration/timing signal) · `429` too many verify attempts (`errorCode: OTP_ATTEMPTS_EXCEEDED`) — see §2.

### `POST /api/v1/auth/otp/resend`
```json
// Request
{ "phoneNumber": "+989123456789" }
// Response — same shape as /otp/request
```
Functionally issues a new code via the same underlying path as `/otp/request` (one application-layer use case, two entry points) — kept as a **separate endpoint** rather than folded into `/request` for two concrete reasons: (1) it lets the Owner App's UI express real user intent distinctly ("Send code" vs. "Resend code" are different buttons in different states), and (2) it gives the backend a place to apply a *stricter* per-phone budget than the initial request if abuse data ever shows resend-spam is a distinct pattern from initial-request-spam. Both endpoints draw from the **same** rate-limit bucket per phone (§2) — resend is not a way to bypass the request limit.

---

## 2. OTP Security Model

| Control | Value | Rationale |
|---|---|---|
| **Expiration** | 120 seconds, configurable via `OTP_TTL_SECONDS` env var | Matches the existing config-via-env-var convention (`JWT_ACCESS_TTL_MINUTES` etc.). Short enough to bound the replay/interception window; long enough for real SMS latency + user read/type time. |
| **Attempt limit** | 5 wrong `/verify` attempts invalidates that code immediately (even if still within TTL) | A 6-digit code has 1,000,000 combinations — without an attempt cap, TTL alone doesn't stop brute force within the window. |
| **Rate limit — per phone** | 3 requests / 10 minutes, 5 / hour | Bounds SMS cost and spam against one target number. Shared budget across `/request` and `/resend` (see §1). |
| **Rate limit — per IP** | 10 OTP requests / hour | Bounds a single attacker spraying requests across *many* phone numbers to burn SMS budget — per-phone limits alone don't catch this pattern. |
| **Rate limit — verify attempts, per phone** | 10 `/verify` calls / 10 minutes | Separate from the "5 wrong guesses voids one code" rule — stops an attacker from requesting many codes in sequence and grinding guesses across all of them. |

**Additional abuse-prevention controls:**
- **Single active code per phone** — issuing a new OTP (via request or resend) immediately invalidates any previous unexpired one. Matches user expectation ("resend" should void the old code) and halves an attacker's live-guessing surface.
- **Codes are cryptographically random**, generated via `java.security.SecureRandom` (not Kotlin's default `Random`, which is not guaranteed cryptographically secure) — same rigor `RandomNumberGenerator` already applies to token generation on the Owner App side.
- **Codes are stored hashed** (SHA-256 is sufficient here — unlike a password, an OTP is single-use, short-lived, and already rate-limited, so BCrypt's deliberate slowness buys little extra and costs real latency on every verify call), never plaintext, in **Redis** — this is Redis's first real consumer in this stack (wired since the initial scope, unused until now, per every prior audit).
- **No account-existence signal** at request time (§1).
- **Structured audit logging** of request/verify attempts (phone, IP, outcome — never the code itself). This directly closes a gap the security backlog has flagged since the first architecture audit ("no audit logging exists") — worth doing here specifically even if the broader backlog item stays open, since a new SMS-cost-bearing endpoint is exactly where its absence would hurt most.
- **New exception → status mapping needed**: `429 Too Many Requests` doesn't exist anywhere in this codebase yet (`GlobalExceptionHandler` has no precedent for it). New `OtpRateLimitException`/`OtpAttemptsExceededException` types, mapped following the exact existing pattern (one more `@ExceptionHandler` block, one more `errorCodeFor()` branch) — small, consistent, no new pattern invented.
- **Flagged for later, not v1**: CAPTCHA or device attestation on `/otp/request`. Per-phone/per-IP rate limiting doesn't fully stop a distributed attacker spraying low-volume requests at many *invalid* numbers purely to burn SMS spend across a botnet of IPs. Real, but out of scope for a first version — noted so it doesn't get silently forgotten.

---

## 3. JWT After OTP Verification

**Claim set:**
```json
{
  "sub": "<userId>",           // unchanged — always present, the one true identity anchor
  "phone": "+989123456789",    // NEW — present whenever the user has a phone on file
  "email": "owner@example.com",// UNCHANGED KEY, now OPTIONAL — present only if this account also has an email (legacy or dual-identity accounts); ABSENT for phone-only accounts
  "role": "MANAGER",           // unchanged
  "type": "ACCESS",            // unchanged
  "iss": "rojan-ai-backend",   // unchanged
  "iat": ..., "exp": ...       // unchanged
}
```
**Still no `salonId` claim** — reaffirming the existing, already-audited rule (tenant context resolved dynamically per-request via ownership, never cached in the token). This proposal does not touch that architecture.

**Authentication method is not encoded as a claim.** A JWT means the same thing regardless of whether it came from `/auth/login` or `/otp/verify` — this is exactly the audit's own finding that everything downstream of issuance is decoupled from login method, and this design preserves that deliberately rather than accidentally.

**One real change required to the resolution chain**, flagged precisely: `RojanUserDetailsService`/`CurrentUserResolver` today resolve the authenticated principal via `userRepository.findByEmail(Email(principal.username))` — i.e., Spring Security's "username" concept **is** the email today. That breaks the moment a phone-only account (no email) exists. Proposed fix: switch the principal's "username" to the **stringified `sub` claim (the userId)** instead of email, and resolve via `UserRepository.findById(UserId)` everywhere request-time identity is needed. `findByEmail`/`findByPhoneNumber` become **login-time-only** lookups (used once, during `/auth/login` or `/otp/verify`, to find *which* account to issue a token for) — never used again for the lifetime of that token. This is a small, mechanical, well-contained change to two files, not a redesign of the security layer.

---

## 4. Owner App Flow

```
Mobile Number  →  OTP  →  JWT  →  Secure Storage  →  Dashboard
```

| Stage | New work | Reused unchanged |
|---|---|---|
| Mobile Number entry | New `LoginStage.PhoneEntry` UI + validation/formatting, new `RequestOtpCommand` | `LoginWindow`'s overall shell, styling, error-display pattern |
| OTP entry | New `LoginStage.OtpEntry` UI (code input, resend countdown, "wrong number" back-nav), new `VerifyOtpCommand`/`ResendOtpCommand` | Same error-display pattern (`ErrorMessage` binding already built) |
| JWT | New `IAuthenticationService.VerifyOtpAsync(phone, code)` calling the new backend endpoint | `AuthResponse` contract (byte-identical — §1), `AuthBootstrapHttpClient` (same "must bypass the generic 401-retry pipeline" reasoning already applies — pre-auth calls, no session yet) |
| Secure Storage | **Nothing** | `BackendSessionService`'s DPAPI-backed persistence — completely agnostic to how the token was obtained |
| Dashboard | **Nothing** | `BackendDashboardRepository`, tenant resolution — both operate purely on the resulting JWT, never on login method |

**New `IAuthenticationService` methods** (additive, mirroring how `SignInWithCredentialsAsync` was added last session — same architecture-boundary rule applies: return plain `Task`, never a Domain type, so Presentation stays clean per `ArchitectureTests`):
```
Task RequestOtpAsync(string phoneNumber, CancellationToken ct = default);
Task ResendOtpAsync(string phoneNumber, CancellationToken ct = default);
Task VerifyOtpAsync(string phoneNumber, string code, CancellationToken ct = default);
```
`SignInAsync`/`SignInWithCredentialsAsync` are untouched — see §5, email/password isn't going away.

**New contracts**: `OtpRequestRequest(PhoneNumber)`, `OtpVerifyRequest(PhoneNumber, Code)` — mirroring `LoginRequest`'s existing shape/style. **No new response contract** — `/otp/verify` reuses the existing `AuthResponse` C# record exactly as-is.

**`LoginViewModel` becomes a small state machine** (`PhoneEntry` → `OtpEntry` → success), not two unrelated ViewModels — they share the phone number and the overall "I am trying to log in" context. Concretely: one ViewModel, one `CurrentStage` enum property the View's `DataTemplate`s switch on, matching a pattern (`ContentControl` + `DataTemplateSelector`-by-state) already used elsewhere in this codebase for dialog regions.

---

## 5. Migration Strategy for Existing Email Users

**Recommendation: Keep — indefinitely, as a fully parallel method. Do not migrate or deprecate as part of this phase.**

| Option | Verdict | Why |
|---|---|---|
| **Keep** (dual identity, permanent) | ✅ **Recommended** | Zero disruption. Every email/password account created and tested this session (including the real, live-verified `owner.e2e@rojan.test`) keeps working with no forced action. New users can register via either phone+OTP or email+password. "Mobile-first" means phone/OTP is the *default, recommended, primary* path for new users — it does not have to mean "mobile-only." |
| **Migrate** (force-link a phone number, eventually make it primary) | ⏸️ Not now — revisit later, as its own decision | Real disruption: requires a mandatory "add your phone" interstitial and a deadline/grace-period policy neither of us has defined. Nothing about this phase requires deciding this yet. |
| **Deprecate** (remove email/password) | ❌ Not recommended, at least not soon | Email/password is fully built, tested, and in active use by this session's own E2E validation work. Removing it forces rework of existing test infrastructure for no product benefit at this stage, and closes off a login method some users may simply prefer (e.g., anyone without reliable SMS reception). |

**What "Keep" concretely means for this build:**
- `users.phone_number` is added as **nullable, unique-when-present** — `email` stays `NOT NULL` on existing rows, untouched.
- New accounts may be created via `/otp/verify` (phone-only, `email` null) **or** `/auth/register` (email-only, `phone_number` null) — both remain first-class, permanently, not one deprecating the other by default.
- `RegisterUserUseCase`/`AuthenticateUserUseCase` (email/password) are **not modified** by this work. This proposal is purely additive on the backend.
- A future, **separately-approved** phase could offer existing email users an *optional* "also add a phone number" prompt if usage data ever motivates it — explicitly not decided or scheduled here.

---

## Open Decisions Requiring Your Sign-Off Before Phase 1 Coding

1. **SMS provider** — a real, paid, external dependency; not something to pick unilaterally. (Audit flagged this; still open.)
2. **First-time-phone auto-registration at `/otp/verify`** (§1) — confirmed as the proposed behavior; flag now if you want registration to instead require a separate explicit step.
3. **Numeric limits in §2** (120s TTL, 5 attempts, 3/10min per-phone, 10/hr per-IP) — reasonable industry-typical defaults, not empirically tuned for this product; treat as a starting point, adjust freely.
4. **`sub`-based principal resolution** (§3) — a real, if small, change to `RojanUserDetailsService`/`CurrentUserResolver`; confirming this is in scope for Phase 1 alongside the new endpoints, not deferred.
5. Confirm the **Keep** recommendation in §5, or state a different migration posture if you disagree.

No code will be written until these are addressed.
