# ROJAN Auth Production Checklist

Audit only — no refresh-token or cookie work implemented here, per instruction.

## JWT Expiration

| Token | Env var | Default | Configured where |
|---|---|---|---|
| Access | `JWT_ACCESS_TTL_MINUTES` | 15 minutes | `application.yml` → `rojan.security.jwt.access-token-ttl-minutes`, bound via `JwtProperties` |
| Refresh | `JWT_REFRESH_TTL_DAYS` | 30 days | `application.yml` → `rojan.security.jwt.refresh-token-ttl-days` |

✅ Both configurable per-environment via env var, sensible non-production-breaking defaults if unset.
⚠️ No revocation mechanism exists — a token is valid for its full TTL regardless of logout/password-change/compromise (already flagged in the prior architecture audit and security backlog).

## Secret Configuration

- `JWT_SECRET` (env) → `rojan.security.jwt.secret` → `JwtProperties.secret: String` (**non-nullable, no default**).
- ✅ **Fail-closed verified:** if `JWT_SECRET` is unset, Spring Boot's `@ConfigurationProperties` binding fails at startup (missing required constructor argument) — the app cannot boot with an unset/blank secret. Confirmed by reading the binding (`data class JwtProperties(val secret: String, ...)` has no default for `secret`) and the inline comment: *"No default on purpose: startup must fail loudly if JWT_SECRET is unset rather than silently signing tokens with a known/weak key."*
- ✅ Signing key built once at `JwtTokenProvider` construction via `Keys.hmacShaKeyFor(jwtProperties.secret.toByteArray())` — HMAC-SHA, key material never logged, never returned in any API response.
- ⚠️ No minimum-length enforcement in code — the doc comment says "must be at least 32 bytes" but nothing rejects a shorter value at startup; a misconfigured short secret would boot successfully with a weaker-than-intended key. Not fixed here (would be a code change beyond audit scope); flagged for the security backlog.

## Environment Variables (auth-relevant)

| Variable | Required | Default | Notes |
|---|---|---|---|
| `JWT_SECRET` | **Yes** | none | App refuses to start without it |
| `JWT_ISSUER` | No | `rojan-ai-backend` | Embedded in every token's `iss` claim, validated on parse |
| `JWT_ACCESS_TTL_MINUTES` | No | `15` | |
| `JWT_REFRESH_TTL_DAYS` | No | `30` | |

Cross-checked against `.env.example` and `DEPLOYMENT.md`'s environment-variable table — consistent, no undocumented auth-relevant variable found.

## Production Profile (`application-prod.yml`)

Relevant to auth/security specifically:
- `springdoc.api-docs.enabled` / `swagger-ui.enabled` → `${SPRINGDOC_ENABLED:false}` — **Swagger UI and the raw OpenAPI JSON are both off by default in prod.** Correct: don't publicly document/expose the full API surface (including exact auth flow shape) by default in production.
- `management.endpoint.health.show-details: never` — tightened from the dev default (`when-authorized`) — no component/dependency detail leaks via `/actuator/health` regardless of caller auth state.
- No auth-specific behavior differs between profiles beyond the above (token TTLs, secret handling, entry-point body are identical in dev/test/prod — same code path, only config values differ per environment).

✅ Both settings correctly tighten production exposure relative to dev.

## Logging Security

Checked for: secrets, raw tokens, passwords, or bearer headers appearing in any log statement.

- **Zero matches** for `log.<level>(...)` statements referencing token/password/secret content anywhere in the codebase (grepped for `password`, `token`, `secret` case-insensitively adjacent to `log.info/debug/warn/error/trace` calls).
- `GlobalExceptionHandler` logs `message` and `traceId` for errors, never the request body or `Authorization` header.
- Production logging levels (`application-prod.yml`): `root: WARN`, `ai.rojan.backend: INFO` — no `DEBUG`/`TRACE` enabled anywhere that might dump request/response bodies (e.g. no Spring `logging.level.org.springframework.web` DEBUG, no Hibernate SQL-parameter logging enabled).
- `AuthController.login`/`.register` never log the raw password anywhere in their call path (`AuthenticateUserUseCase`/`RegisterUserUseCase` pass it directly to `PasswordEncoderPort`, no logging in between).

✅ **No sensitive-data logging found.**

## Summary

| Item | Status |
|---|---|
| JWT expiration configured, sane defaults | ✅ |
| Secret fails closed if unset | ✅ |
| Secret minimum-length enforced at startup | ⚠️ Not enforced — documented expectation only |
| Env vars documented and consistent | ✅ |
| Prod profile tightens exposure (Swagger, actuator) | ✅ |
| No sensitive data in logs | ✅ |
| Refresh-token revocation | ❌ Does not exist (out of scope for this task — see security backlog) |
| Cookie-based session | ❌ Does not exist (out of scope for this task — see security backlog) |

**Verdict: production-viable as a stateless bearer-token API**, with two known, already-documented structural gaps (no revocation, no length-enforcement on the secret) carried forward to the security backlog rather than fixed here, per this task's explicit "do not implement" constraints.
