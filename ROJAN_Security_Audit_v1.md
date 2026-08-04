# ROJAN Security Audit v1

Scope: JWT validation, CORS, CSRF, rate limiting, secret management — current implementation only. No major security changes were made as part of this audit, per instruction; the one code change in this pass (`ApiError.errorCode`, see the implementation report) is an error-formatting change, not a security-control change.

---

## 1. JWT Validation

**Files:** `infrastructure/security/JwtTokenProvider.kt`, `JwtAuthenticationFilter.kt`, `JwtProperties.kt`.

- Algorithm: HMAC-SHA (`io.jsonwebtoken`/jjwt, `Keys.hmacShaKeyFor`).
- Signing key: `rojan.security.jwt.secret` (env `JWT_SECRET`) — **no default value**, app refuses to boot if unset.
- Validation on every request (`JwtAuthenticationFilter`, runs before `UsernamePasswordAuthenticationFilter`):
  - Signature verified (`Jwts.parser().verifyWith(signingKey)`).
  - Issuer required and checked (`requireIssuer(jwtProperties.issuer)`).
  - Expiry checked implicitly by the parser (`ExpiredJwtException` caught → treated as invalid).
  - Token `type` claim checked at the use site: `RefreshTokenUseCase` rejects a token whose `type != REFRESH`; `JwtAuthenticationFilter` only authenticates on `type == ACCESS` (a refresh token presented as a bearer token is silently *not* authenticated — falls through to anonymous, which then hits `.anyRequest().authenticated()` and 401s, rather than being explicitly rejected with a distinct error).
  - Malformed/expired/invalid tokens are caught and normalized to `InvalidTokenException`, never leak parser internals to the client.
- **No revocation mechanism.** No denylist, no token store (Redis is present in the stack but unused by the auth path — confirmed by inspection, `JwtTokenProvider`/`RefreshTokenUseCase` take no cache/Redis dependency). A leaked or stolen token is valid until natural expiry regardless of logout, password change, or admin action. There is no logout endpoint (consistent with pure stateless JWT, but worth naming as a real gap for a production financial-data dashboard).
- **No key rotation mechanism.** Single static secret, no `kid` claim, no multi-key verification. Rotating `JWT_SECRET` invalidates every outstanding token instantly (all users forced to re-login) rather than allowing graceful overlap.

**Assessment:** validation itself (signature, expiry, issuer, type-confusion resistance) is sound and follows current best practice for stateless JWT. Revocation and rotation are the two structural gaps — both are architecture decisions (would need a token store), not bugs, and are listed under Architecture Decisions Required in the implementation report.

## 2. CORS

**Finding: not configured.** Verified by:
- No `CorsConfigurationSource` bean anywhere in the codebase.
- No `.cors(...)` call in `SecurityConfig`'s `HttpSecurity` chain (only `.csrf`, `.sessionManagement`, `.exceptionHandling`, `.authorizeHttpRequests`, `.addFilterBefore` are configured).
- No `@CrossOrigin` annotation on any controller.

**Consequence:** a browser-based client on a different origin than the API cannot call it (blocked by the browser's same-origin policy, no `Access-Control-Allow-Origin` header is ever sent). This is a non-issue if ROJAN Web is served same-origin via the Nginx reverse proxy documented in `DEPLOYMENT.md` (Nginx terminates TLS and proxies `/api/*` under the same domain Web is served from); it becomes a hard blocker the moment Web is hosted on a separate domain/subdomain from the API and calls it directly from the browser. **This needs an explicit decision** (see implementation report) before Web integration proceeds if same-origin deployment isn't guaranteed.

## 3. CSRF

`.csrf { it.disable() }` in `SecurityConfig`. **Appropriate for the current auth model** — CSRF exploits ambient credentials (cookies) that the browser attaches automatically; this API has no session cookie today, authentication is an explicit `Authorization` header the browser never attaches on its own, so there is no CSRF-able credential to protect. This is standard, correct practice for bearer-token APIs, not an oversight.

**This changes if HttpOnly cookies are introduced** (per the Web Authentication Bridge preparation in the implementation report): a cookie *is* ambient and *is* CSRF-exploitable. Re-enabling CSRF protection (double-submit token or Spring Security's synchronizer token, scoped only to state-changing requests) becomes a required, not optional, change at that point — flagged here so it isn't missed when that migration happens, but **not implemented now**, since the cookie bridge itself hasn't been implemented yet.

## 4. Rate Limiting

**Finding: none exists.** No Bucket4j, no Resilience4j rate limiter, no custom throttling filter, no dependency on any rate-limiting library found in any `build.gradle.kts`. Nginx config (`docker/nginx/`) was not re-audited line-by-line in this pass for `limit_req`/`limit_conn` directives — worth a follow-up check specifically there, since infra-level rate limiting could exist independent of the application.

**Most exposed endpoints:**
- `POST /api/v1/auth/login` — no lockout, no delay, no CAPTCHA; open to credential-stuffing/brute-force at whatever rate a client can send requests.
- `POST /api/v1/auth/register` — open to registration spam.
- `POST /api/v1/auth/refresh` — open to abuse if a refresh token is ever leaked (compounds the "no revocation" gap in §1).
- `GET /api/v1/public/**` — unauthenticated by design (public website API), so it's the cheapest possible target for scraping/DoS-style abuse relative to authenticated endpoints.

Not fixed in this pass per instruction ("do not implement major security changes unless required") — listed as an open decision.

## 5. Secret Management

- `JWT_SECRET`, `DB_PASSWORD`, `REDIS_PASSWORD` — all environment-variable-driven, **zero hardcoded secrets found** in source, `application.yml`, or `application-prod.yml` (all reference `${VAR}` placeholders only).
- `JWT_SECRET` has no default and fails startup loudly if unset (`JwtProperties.secret: String` non-nullable, no default value) — correct fail-closed behavior, prevents accidentally running with a known/weak signing key.
- `.env.example` documents every required variable without real values; `DEPLOYMENT.md`'s checklist explicitly confirms `.env` itself is gitignored before deployment.
- Password storage: BCrypt (`BCryptPasswordEncoderAdapter`, Spring Security's standard encoder) — salted, one-way, industry standard.

**Assessment:** secret handling is solid — no findings here.

## 6. Public Endpoints (attack surface as currently exposed)

Exact list from `SecurityConfig.PUBLIC_ENDPOINTS`:
```
/api/v1/auth/**
/api/v1/public/**
/actuator/health
/actuator/health/**
/v3/api-docs/**
/swagger-ui/**
/swagger-ui.html
```
Everything else requires a valid bearer token. Actuator exposure is minimal (`health,info` only; `show-details: never` in prod). Swagger/OpenAPI docs are disabled by default in prod (`SPRINGDOC_ENABLED` env-gated, defaults `false`) — correct, since publicly exposing full API surface documentation isn't appropriate for production by default.

One implementation note relevant to this attack surface: `PublicWebsiteController` (under `/api/v1/public/**`) currently returns static/stub data regardless of the requested tenant slug — not itself a security issue, but means this public surface isn't yet doing real tenant lookups that would need their own input validation once implemented.

## Summary

| Area | Status |
|---|---|
| JWT validation | Sound. No revocation/rotation (architecture decision, not a bug). |
| CORS | Not configured — fine only if Web stays same-origin via Nginx; needs a decision otherwise. |
| CSRF | Correctly disabled for the current bearer-token model; must be revisited if/when cookies are introduced. |
| Rate limiting | Absent everywhere in the app layer; unaudited at the Nginx layer in this pass. |
| Secret management | Clean — no findings. |

No code changes were made in this security review, per instruction.
