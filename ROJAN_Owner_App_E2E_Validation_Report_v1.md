# ROJAN Owner App — Real Backend E2E Validation Report v1

## Overall Result: **BLOCKED — not executable in this environment**

Per instruction ("If any failure: Only report. Do not redesign"), this is a report only — nothing was changed, redesigned, or fixed. This is not a "some steps failed" result; it's a "the validation could not be attempted" result, and I'm not going to dress that up as partial success.

---

## Why this can't be run here

A genuine E2E validation of this flow requires two things this environment does not have:

1. **A running, real `ROJAN_Backend` instance with a real database.** Checked directly this session:
   - No Docker (`docker` command not found — consistent with every prior session).
   - No Postgres or Redis running as a native process or Windows service (`tasklist`/`sc query` both confirm nothing).
   - Nothing listening on port 8080 (`netstat` confirms).
   - I could build the backend jar and run `java -jar`, but it would immediately fail at startup — `spring.datasource.url` points at a Postgres that doesn't exist here, and there is no way to stand one up without Docker.

2. **A way to see and interact with a WPF window.** `ROJAN_Desktop` is a native Windows GUI app. I have no screen/GUI automation tool, no screenshot capability, and no way to type into a login form, click "Sign In," or visually confirm the Dashboard rendered correctly. This is categorically different from `dotnet build`/`dotnet test`, which are headless and which I've used successfully all session — those verify the *code compiles and its logic is correct*, not that a human would see a working screen.

Given both are missing, **every one of the 7 requested steps is blocked**, not just some of them. I'm not going to fabricate network logs, API responses, or a PASS on any step — that would be reporting something I didn't actually observe.

---

## Step-by-step status

| # | Step | Status | Why |
|---|---|---|---|
| 1 | Application Start | **BLOCKED** | No display/GUI automation to launch and observe the app |
| 2 | Login with real Backend credentials | **BLOCKED** | No running Backend to authenticate against; no way to type credentials into the form |
| 3 | Verify JWT received | **BLOCKED** | Depends on step 2 |
| 4 | Verify secure session persistence | **BLOCKED** | Depends on step 2 |
| 5 | Open Dashboard | **BLOCKED** | Depends on step 2 |
| 6 | Verify `GET /api/v1/dashboard/insights` | **BLOCKED** | No running Backend to call |
| 7 | Verify rendering (Revenue/Bookings/Customers/Services/Recommendations) | **BLOCKED** | No GUI observation capability |

**Network logs / API responses:** none — no request was ever made, because there is no server to make one against. There is nothing here to paste; fabricating sample output would misrepresent what happened.

---

## What *is* verified (from prior sessions, not re-claimed as this task's result)

To be precise about what's actually known vs. blocked here — restating, not re-testing:

- `ROJAN_Backend`'s `/api/v1/dashboard/insights` and `/api/v1/auth/login` are implemented and covered by automated integration tests (real HTTP layer, embedded Postgres) — verified in earlier sessions, unrelated to this task's request for a *real* deployed backend.
- `ROJAN_Desktop`'s login/dashboard code compiles, and its logic is covered by 2,085 passing unit/integration tests (`LoginViewModel` error handling, `BackendAuthenticationService`/`BackendSessionService` against a faked HTTP transport, `BackendDashboardRepository`'s response mapping) — verified last session.
- Neither of these is what this task asked for. This task asked for the *real* thing: a live server, a live GUI, credentials that actually round-trip. That's what's blocked.

---

## Remaining Blockers

1. **No Backend deployment reachable from this environment.** Someone needs to either deploy `ROJAN_Backend` (per `DEPLOYMENT.md`/the established `scp` + `docker compose` flow) somewhere reachable, or set up Postgres/Redis + run the jar locally on a machine that has them.
2. **No way to run this validation from this sandbox at all**, even with a reachable backend — it requires a human (or a GUI-automation-capable environment) actually operating the Windows app.
3. **`ROJAN_API_BASE_URL` / the Owner App's Development-Production environment setting** must point at whatever real backend is used for this test (see `ROJAN_Owner_App_Login_Completion_Report_v1.md`'s Settings section from last session).

## What I'd need to actually do this

Either:
- **You run it, I don't** — the honest default. Below is the exact manual procedure.
- **You give me a reachable backend URL + a way to drive the GUI** — if there's a remote/VM environment with GUI access I can be pointed at, this becomes attemptable. Nothing like that has been provided so far this session.

### Manual procedure (for whoever runs this)

1. Deploy/start `ROJAN_Backend` somewhere real (Postgres + Redis reachable, `JWT_SECRET` set).
2. Confirm it's reachable: `curl -i http://<host>/actuator/health`.
3. On the machine running `ROJAN_Desktop`: Settings → Server Environment → point at that host (or set `ROJAN_API_BASE_URL`), restart the app.
4. Launch the app. Expect: no persisted session yet → Login screen appears.
5. Enter real credentials for an account that owns exactly one salon (matches the backend's tenant-resolution rule — 0 or 2+ salons will 404/409 on the dashboard, by design, not a bug).
6. Confirm Login succeeds → app proceeds to the Dashboard.
7. While logged in, capture the real request: browser devtools won't apply here, but you can watch the backend's own access log, or temporarily add a debug breakpoint in `HttpApiClient.SendOnceAsync`, to confirm the `Authorization: Bearer <token>` header and the exact `GET /api/v1/dashboard/insights` response body.
8. Visually confirm the Dashboard shows real numbers (not the old static "128 / 42 / 124,000,000 تومان" values `FakeDashboardRepository` used to show) and that recommendation text appears if the account has any completed bookings.
9. Restart the app without signing out — confirm it goes straight to the Dashboard (session restored from DPAPI storage, not asking to log in again).
10. Settings → Sign Out → confirm the app shows the session-ended message and exits; relaunch → confirms Login screen reappears.

Report back the actual output of steps 5-10 and I'll assess it for real, rather than assume it.
