# ROJAN Backend — Authentication Error Contract Implementation Report

## Goal

Every authentication failure returns:
```
HTTP 401
{"errorCode":"AUTH_UNAUTHORIZED","message":"Authentication required"}
```

## Change

**File:** `infrastructure/src/main/kotlin/ai/rojan/backend/infrastructure/security/SecurityConfig.kt`

Replaced `HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)` (status-only, empty body) with a custom `AuthenticationEntryPoint` lambda that writes the fixed JSON body above and sets `Content-Type: application/json`.

```kotlin
.exceptionHandling {
    it.authenticationEntryPoint { _, response, _ ->
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = "application/json"
        response.writer.write(
            """{"errorCode":"AUTH_UNAUTHORIZED","message":"Authentication required"}""",
        )
    }
}
```

This fires for **every** unauthenticated request to a non-public endpoint, app-wide — it's the single entry point Spring Security uses whenever `.anyRequest().authenticated()` rejects a request, so no per-endpoint wiring was needed.

### Scope discipline (per your constraints)
- **JWT architecture:** untouched — `JwtTokenProvider`, `JwtAuthenticationFilter`, `JwtProperties`, token claims, TTLs, all unchanged.
- **Refresh tokens:** untouched — no new endpoint, no new token type, no change to `RefreshTokenUseCase`.
- **Cookie bridge:** not implemented — still pure `Authorization: Bearer` header auth.
- **Rest of `SecurityConfig`:** `.csrf`, `.sessionManagement`, `PUBLIC_ENDPOINTS`, filter ordering — all unchanged. Only the `.exceptionHandling` block's entry point changed.

### Body shape note
This is a fixed 2-field body (`errorCode`, `message` only) — narrower than `ApiError` (`timestamp`/`status`/`error`/`errorCode`/`message`/`path`/`traceId`), which `GlobalExceptionHandler` uses for every other error type. This matches your exact spec literally, but means the API now has two error-body conventions: this minimal one for auth-layer 401s (fires before any controller runs), and the fuller `ApiError` for everything `GlobalExceptionHandler` catches. This resolves the blocker flagged in the prior architecture report ("`AUTH_UNAUTHORIZED` cannot be delivered today").

## Tests

Checked every existing test asserting on `HttpStatus.UNAUTHORIZED` first (`AuthenticationFlowIntegrationTest`, `SalonManagementFlowIntegrationTest`, `DashboardInsightsFlowIntegrationTest`) — all of them asserted status code only, none depended on the old empty body, so none broke.

Strengthened one test to lock in the new contract:
- `DashboardInsightsFlowIntegrationTest`: `insights requires a bearer token, and returns the standard AUTH_UNAUTHORIZED contract` now asserts the exact response body in addition to the `401` status.

```
./gradlew clean build
```
**BUILD SUCCESSFUL** — full suite, all modules, including the strengthened test and every pre-existing 401 assertion across the bootstrap integration suite.

```
./gradlew bootJar
```
**BUILD SUCCESSFUL** — `bootstrap/build/libs/bootstrap-0.1.0-SNAPSHOT.jar` regenerated with this change.

## Files Changed

| File | Change |
|---|---|
| `infrastructure/src/main/kotlin/ai/rojan/backend/infrastructure/security/SecurityConfig.kt` | Custom `AuthenticationEntryPoint` emitting the `AUTH_UNAUTHORIZED` JSON body |
| `bootstrap/src/test/kotlin/ai/rojan/backend/bootstrap/DashboardInsightsFlowIntegrationTest.kt` | Strengthened the 401 test to assert the new body |

## Blockers

None.

## Next Steps

- Not deployed — same standing limitation as prior sessions (no SSH credentials in this environment). Deploy via the established `scp` jar + `docker compose build/up app` flow, then verify with `curl -i` that an unauthenticated request to any protected endpoint now returns the JSON body, not an empty one.
- Consider whether `GlobalExceptionHandler`'s `ApiError` (used for 404/409/etc.) should eventually be reconciled with this minimal shape for full consistency — noted, not acted on, since you didn't ask for that here.
