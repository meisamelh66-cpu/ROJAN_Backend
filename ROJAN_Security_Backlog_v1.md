# ROJAN Security Backlog v1

Documentation only — nothing below is implemented in this task, per instruction. Each item consolidates findings already surfaced across the architecture audit, security audit, and this task's auth production checklist, so they live in one forward-looking place instead of scattered across historical reports.

---

## 1. HttpOnly Cookie Session

**Current state:** tokens returned as JSON body fields, stored/attached by the client manually. No `Set-Cookie` anywhere in the codebase.

**Why it matters:** an access token held in JS-accessible storage (localStorage, or memory the app can still be tricked into exposing) is readable by any successfully injected script (XSS). An `HttpOnly` cookie removes that specific attack surface.

**What it requires when picked up:**
- `AuthController.login`/`.refresh` writing `Set-Cookie` (in addition to or instead of the JSON body — needs a product decision).
- `SecurityConfig` CSRF must be **re-enabled** the moment a cookie carries auth (currently correctly disabled for the header-only model — this is not a bug today, only a precondition that flips).
- Explicit `SameSite`, `Secure`, domain/path scoping decisions.
- Coordination with Team Web on the Next.js session-bridging side — this is a two-sided contract, not backend-only work.

**Prior analysis:** `ROJAN_Backend_Architecture_Implementation_Report.md` §4.

## 2. Refresh Token Rotation

**Current state:** refresh tokens are stateless JWTs with a 30-day TTL, validated by signature/expiry/type only. No server-side store, so:
- A still-valid refresh token can be exchanged repeatedly with no reuse detection.
- There is no way to revoke one before natural expiry (logout, password change, suspected compromise all currently do nothing to invalidate outstanding tokens).

**What it requires when picked up:** a token store (Redis is already wired into the stack, unused today, and is the natural fit) recording issued/used refresh-token IDs (`jti` claim already exists on every token), rotation-on-use, and reuse-detection-triggered revocation of the whole token family.

## 3. Rate Limiting

**Current state:** none, anywhere — confirmed no Bucket4j/Resilience4j/custom throttling filter in any module's dependencies or code.

**Highest-priority targets:**
- `POST /api/v1/auth/login` — brute-force/credential-stuffing exposure.
- `POST /api/v1/auth/register` — registration spam.
- `POST /api/v1/auth/refresh` — compounds item 2 above if a refresh token ever leaks.
- `GET /api/v1/public/**` — unauthenticated by design, cheapest target for scraping.

**Note:** Nginx-layer rate limiting (`limit_req`/`limit_conn` in `docker/nginx/`) has not been audited as part of any pass so far — worth checking whether infra-level protection already exists before assuming this is fully unprotected end-to-end.

## 4. CORS Policy

**Current state:** unconfigured — no `CorsConfigurationSource` bean, no `.cors()` call in `SecurityConfig`, no `@CrossOrigin` anywhere.

**Why it's not yet a fire:** if ROJAN Web is served same-origin (via the Nginx reverse proxy `DEPLOYMENT.md` describes), browsers never trigger CORS restrictions in the first place. It becomes a hard blocker the moment Web calls this API from a different origin (very likely true during local Web development even if prod stays same-origin).

**What it requires when picked up:** an explicit `CorsConfigurationSource` bean — allowed origins (dev + prod, likely different), allowed methods/headers, and a decision on whether cookies/credentials mode is ever needed (ties into item 1).

## 5. Audit Logging

**Current state:** standard application logging exists (`GlobalExceptionHandler` logs every error with a `traceId`; Spring Security's own filter chain doesn't emit a dedicated audit trail). There is no **security-event-specific** log stream — no structured record of "who logged in when," "who was denied access to what," "who changed a password," etc., separate from generic error/debug logs.

**Why it matters for a multi-tenant financial dashboard:** if a salon owner disputes seeing another salon's data, or an account is suspected compromised, there's currently no dedicated, queryable trail to investigate from — only whatever incidentally ended up in general application logs.

**What it requires when picked up:** a decision on scope (login success/failure, token refresh, 403/404/409 on ownership-gated endpoints, password changes) and a storage target (structured log sink vs. a dedicated audit table) — not something to bolt on ad hoc without agreeing the scope first.

---

## Prioritization note (not a decision — for whoever triages this backlog)

Items 2 (refresh revocation) and 3 (rate limiting on auth endpoints) are the two with the most direct exposure given the API is about to receive real external traffic via Web integration. Items 1 (cookie bridge) and 4 (CORS) are coupled — deciding Web's hosting topology (same-origin via Nginx vs. separate domain) resolves whether CORS is even needed, and whether the cookie bridge is worth building depends on Web's actual XSS risk profile (Next.js SSR vs. pure SPA). Item 5 (audit logging) is lower urgency but cheapest to scope early, before log volume grows.

This ordering is offered for context only — actual prioritization is a product/security decision, not something this backlog document makes unilaterally.
