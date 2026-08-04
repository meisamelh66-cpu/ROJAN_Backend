# ROJAN End-to-End Test Plan v1

Scenario:
```
User Registration/Login
        |
        v
   Receive JWT
        |
        v
  Access Dashboard
        |
        v
  Fetch Metrics
        |
        v
Generate AI Recommendation
```

## Automated coverage (already exists, re-verified this session)

| Step | Test | File |
|---|---|---|
| Registration | `register, login, refresh, and authenticated access all work end-to-end` | `bootstrap/.../AuthenticationFlowIntegrationTest.kt` |
| Login → JWT | same test — asserts `accessToken`/`refreshToken` non-blank | same |
| Access Dashboard (auth check) | `insights requires a bearer token, and returns the standard AUTH_UNAUTHORIZED contract` | `bootstrap/.../DashboardInsightsFlowIntegrationTest.kt` |
| Access Dashboard (tenant resolution: 404/409/200) | `an owner with no salon gets 404...` / `an owner with two salons gets 409...` / `an owner with exactly one salon gets a complete, zeroed empty-state response...` | same |
| Fetch Metrics (revenue/bookings/customers/services math) | `computes revenue, booking counts, customers and per-service breakdown for the current month`, `today's revenue only counts bookings starting today` | `application/.../dashboard/GetDashboardInsightsUseCaseTest.kt` |
| Generate AI Recommendation | 14 rule-specific tests (`RuleBasedRecommendationEngineTest.kt`) + `computes revenue...` asserts `recommendations.any { it.type == REVENUE_GROWTH }` end-to-end through the use case | `application/.../dashboard/RuleBasedRecommendationEngineTest.kt`, `GetDashboardInsightsUseCaseTest.kt` |

**All of the above ran as part of this task's `./gradlew clean build` and passed.** This scenario is *not* currently expressed as a single, named end-to-end test method — it's covered across the files above, split by layer (application-layer unit tests for metrics/recommendation math, bootstrap-layer integration tests for the real HTTP+auth+DB path). No new test was written to consolidate them into one method, since doing so would duplicate existing, already-passing coverage rather than add new verification — consistent with "do not create new features" for this task.

## Manual verification steps (require a running deployed instance — not executable from this environment)

For whoever runs this against a live server (staging or prod) after deployment:

1. **Register:**
   ```bash
   curl -X POST https://<host>/api/v1/auth/register \
     -H "Content-Type: application/json" \
     -d '{"email":"e2e-test@example.com","password":"supersecret123","fullName":"E2E Test","role":"MANAGER"}'
   ```
   Expect `201`.

2. **Login → JWT:**
   ```bash
   curl -X POST https://<host>/api/v1/auth/login \
     -H "Content-Type: application/json" \
     -d '{"email":"e2e-test@example.com","password":"supersecret123"}'
   ```
   Expect `200`, capture `accessToken`.

3. **Access Dashboard without a salon yet:**
   ```bash
   curl -i https://<host>/api/v1/dashboard/insights -H "Authorization: Bearer $TOKEN"
   ```
   Expect `404`, `errorCode: SALON_NOT_FOUND`.

4. **Create a salon:**
   ```bash
   curl -X POST https://<host>/api/v1/salons \
     -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"name":"E2E Salon","phone":"+1 555 0100","address":"1 Main St"}'
   ```
   Expect `201`.

5. **Fetch Metrics:**
   ```bash
   curl -i https://<host>/api/v1/dashboard/insights -H "Authorization: Bearer $TOKEN"
   ```
   Expect `200`, all-zero empty state (`bookings.total: 0`, `recommendations: []`) since no bookings exist yet.

6. **Generate AI Recommendation (requires real activity):**
   - Create a service category, a service, a specialist, working hours, and at least one booking that reaches `COMPLETED` status (via `confirm` then `complete`) — see `bootstrap/.../BookingEngineFlowIntegrationTest.kt` for the exact sequence.
   - Re-fetch `GET /api/v1/dashboard/insights` — expect `recommendations` to contain at least the `SERVICE_PERFORMANCE` entry (fires whenever any service has completed bookings), and `REVENUE_GROWTH`/`BOOKING_GROWTH` if a comparable prior-month baseline exists.

7. **Negative case — no token:**
   ```bash
   curl -i https://<host>/api/v1/dashboard/insights
   ```
   Expect `401`, body exactly `{"errorCode":"AUTH_UNAUTHORIZED","message":"Authentication required"}`.

## Pass/fail criteria

- [ ] Step 1-2: registration + login succeed, valid JWT returned
- [ ] Step 3: dashboard correctly reports "no salon" before one exists
- [ ] Step 4: salon creation succeeds
- [ ] Step 5: dashboard returns clean empty-state metrics, not an error
- [ ] Step 6: at least one rule-based recommendation appears once real activity exists
- [ ] Step 7: unauthenticated access is rejected with the exact standardized body

## Status

Automated portion: **executed and passing** as of this task's build run. Manual/live portion: **not executed** — no deployed instance reachable from this environment. Whoever deploys this build should run steps 1-7 once, and can then rely on the automated suite for all subsequent regression checking.
