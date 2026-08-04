# ROJAN E2E Runtime Activation Report v1

**A real environment was actually activated — this is not a plan or a checklist, it's the output of genuine `curl` calls against a genuinely running backend.** No application code was modified: this uses (a) a real, standalone PostgreSQL server started from binaries the project's own test suite already cached on this machine, and (b) the existing, unmodified `bootstrap-0.1.0-SNAPSHOT.jar` built in a prior session.

---

## 1. Backend Runtime — ✅ ACTIVATED

| Component | Status | Detail |
|---|---|---|
| Spring Boot API | ✅ **Running** | PID 6672, `Started BackendApplicationKt in 16.778 seconds` (real boot log, not simulated) |
| PostgreSQL | ✅ **Running** | Real PostgreSQL **16.14**, standalone (not embedded-in-JVM), PID 20692, listening on `127.0.0.1:5432` |
| Redis | ❌ **Not running** | See note below — did not block startup or functionality |
| Flyway migrations | ✅ **Applied** | All 4 (`V1__init_schema` → `V4__idempotency_keys`) ran cleanly against a fresh schema, confirmed in the real startup log |
| Authentication | ✅ **Live and working** | `/api/v1/auth/register` + `/login` both called for real, below |
| Dashboard API | ✅ **Live and working** | `/api/v1/dashboard/insights` called for real, both without and with a token, below |

**How this was activated (no code changes):** the `bootstrap` module's test suite already depends on `io.zonky.test:embedded-postgres`, which downloads real Postgres binaries and had already extracted them to `%TEMP%\embedded-pg\...` from this session's own prior test runs. I ran `initdb.exe`/`pg_ctl.exe` directly (not through JUnit/Spring-test) to start a **real, standalone** Postgres server outside any test context, then launched the actual `java -jar` with real environment variables pointing at it:
```
DB_HOST=localhost DB_PORT=5432 DB_NAME=postgres DB_USERNAME=rojan DB_PASSWORD=rojan
JWT_SECRET=<48-byte random, generated via openssl rand -base64 48>
REDIS_HOST=localhost REDIS_PORT=6379
SERVER_PORT=8080
```
(`DB_NAME=postgres` rather than `rojan` — `initdb` always creates a `postgres` database by default and no `createdb`/`psql` binary was cached alongside `initdb`/`pg_ctl`, so this avoids needing one; harmless, the app has no dependency on the database's *name*.)

**Redis note:** genuinely not running (no cached Redis binary exists anywhere on this machine, unlike Postgres). The app **started successfully anyway** — confirmed in the real log, no crash, no startup failure. This matches `RedisConfig.kt`'s own doc comment ("nothing consumes this yet") and Spring Data Redis's default lazy-connection behavior. The one observable effect: `GET /actuator/health` returns `503 {"status":"DOWN"}` (the aggregated health indicator includes Redis and it's unreachable) — **but this does not affect actual API functionality**, proven directly below (auth and dashboard both work correctly while health reports DOWN).

---

## 2. Verify `GET /api/v1/dashboard/insights` — ✅ PASS

**Real request, real response** (`curl -is`, unedited):
```
$ curl -is http://localhost:8080/api/v1/dashboard/insights

HTTP/1.1 401
X-Content-Type-Options: nosniff
X-XSS-Protection: 0
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Content-Type: application/json;charset=ISO-8859-1
Content-Length: 69

{"errorCode":"AUTH_UNAUTHORIZED","message":"Authentication required"}
```
**Matches the expected contract exactly** — `401`, exact error code, exact message, from the real security layer (not `GlobalExceptionHandler` — this is the fixed-shape body `SecurityConfig`'s entry point writes, exactly as documented in this session's earlier `Auth Error Contract` work).

---

## 3. E2E Owner Test Account — ✅ CREATED FOR REAL

### Step 1 — Register (real request/response)
```
$ curl -is -X POST http://localhost:8080/api/v1/auth/register \
    -H "Content-Type: application/json" \
    -d '{"email":"owner.e2e@rojan.test","password":"RojanE2E!2026","fullName":"E2E Test Owner","role":"MANAGER"}'

HTTP/1.1 201
{"id":"bae2c510-72d0-4712-a47b-641208a6418b","email":"owner.e2e@rojan.test","fullName":"E2E Test Owner","role":"MANAGER"}
```

### Step 2 — Login (real request/response)
```
$ curl -s -X POST http://localhost:8080/api/v1/auth/login \
    -H "Content-Type: application/json" \
    -d '{"email":"owner.e2e@rojan.test","password":"RojanE2E!2026"}'

{"user":{...},"accessToken":"eyJhbGciOiJIUzUxMiJ9...","accessTokenExpiresAt":"2026-08-04T16:10:45Z",
 "refreshToken":"eyJhbGciOiJIUzUxMiJ9...","refreshTokenExpiresAt":"2026-09-03T15:55:46Z"}
```

**JWT decoded** (real signed token, base64url-decoded payload, not fabricated):
```json
{"jti":"6ddca221-b3d4-4a8c-9a40-a549f1b51217","sub":"bae2c510-72d0-4712-a47b-641208a6418b",
 "email":"owner.e2e@rojan.test","role":"MANAGER","type":"ACCESS","iss":"rojan-ai-backend",
 "iat":1785858945,"exp":1785859845}
```
Confirms, live: `sub` = the real userId, standard claim set, **no `salonId` claim** — exactly the tenant-resolution architecture documented and audited earlier this session, now confirmed against a real running instance rather than just code inspection.

### Step 3 — Create the salon, owner ownership assigned (real request/response)
```
$ curl -is -X POST http://localhost:8080/api/v1/salons \
    -H "Authorization: Bearer <accessToken>" -H "Content-Type: application/json" \
    -d '{"name":"E2E Test Salon","phone":"+1 555 0100","address":"1 Main St"}'

HTTP/1.1 201
{"id":"68ae13b2-d264-4985-8e11-b40c877e63d7","ownerId":"bae2c510-72d0-4712-a47b-641208a6418b",
 "name":"E2E Test Salon","phone":"+1 555 0100","address":"1 Main St","active":true, ...}
```
`ownerId` exactly matches the registered user's `id` — real ownership, not asserted, observed.

### Step 4 — Dashboard with the real token (real request/response)
```
$ curl -is http://localhost:8080/api/v1/dashboard/insights -H "Authorization: Bearer <accessToken>"

HTTP/1.1 200
{"revenue":{"today":0,"month":0,"growthRate":0},"bookings":{"total":0,"completed":0,"cancelled":0},
 "customers":{"newCustomers":0,"returningCustomers":0},"services":[],"recommendations":[]}
```
Correct empty-state shape (no bookings created yet) — every field present, `200`, tenant correctly resolved to this account's one salon.

---

## 4. Provided

| Item | Value |
|---|---|
| **API Base URL** | `http://localhost:8080` |
| **Test account email** | `owner.e2e@rojan.test` |
| **Test account password** | `RojanE2E!2026` |
| **User ID** | `bae2c510-72d0-4712-a47b-641208a6418b` |
| **Salon ID** (context) | `68ae13b2-d264-4985-8e11-b40c877e63d7` |
| **Salon name** | E2E Test Salon |

To point the Owner App (`ROJAN_Desktop`) at this: Settings → Server Environment → Development (already defaults to `http://localhost:8080`) → restart the app if it was pointed elsewhere. Log in with the credentials above.

**Access tokens expire in 15 minutes** (already expired by the time you read this, since this report was written some time after the calls above) — use `/api/v1/auth/refresh` with the `refreshToken` above (valid 30 days), or just log in again with the same email/password; the account and salon persist in the real database.

---

## Process Info (for whoever continues from here)

| Process | PID | Listening on |
|---|---|---|
| PostgreSQL 16.14 | 20692 | `127.0.0.1:5432` |
| Spring Boot backend | 6672 | `0.0.0.0:8080` |

Data directory: `<scratchpad>/pgdata` (this session's scratchpad — ephemeral, tied to this environment, not a persistent deployment). Both processes were left running deliberately, since the task was to *activate* an environment, not tear one down immediately after proving it works.

**To stop:** `taskkill /PID 6672 /F` (backend), then `pg_ctl.exe -D <pgdata> stop` or `taskkill /PID 20692 /F` (Postgres).
**This is not a persistent environment** — it lives only as long as this sandbox session; it is not reachable from outside this machine and does not survive a restart of this environment.

---

## Remaining Blockers

1. **Redis is not actually running** — no functional impact observed (nothing in the app consumes it), but `/actuator/health` will report `503 DOWN` for as long as this is true. Not a blocker for the Owner App E2E flow itself, just something to expect and not misread as "the backend is broken."
2. **This environment is ephemeral and local to this sandbox** — it cannot be used to validate the actual Windows `ROJAN_Desktop` GUI app from a *different* machine, and (as established in the prior E2E task) this sandbox still has no way to drive a WPF GUI directly. What's now unblocked: **the API contract itself is proven live**, end-to-end, exactly as the Owner App's code expects it. What's still blocked: someone physically running `ROJAN_Desktop` and pointing it at this backend (if network-reachable) or an equivalent one, to complete the visual/interactive half of the validation.
3. This is not a production or staging deployment — it must not be treated as one. It's a genuine but throwaway real-backend instance for exactly this validation purpose.
