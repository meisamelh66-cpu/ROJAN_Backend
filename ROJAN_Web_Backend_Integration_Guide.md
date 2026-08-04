# ROJAN Web ↔ Backend Integration Guide

For Team Web. Everything below reflects the current, verified backend implementation — nothing here is aspirational.

---

## 1. Login Flow

```
POST /api/v1/auth/login
    |
    v
JWT (accessToken + refreshToken)
```

**Request:**
```http
POST /api/v1/auth/login
Content-Type: application/json

{
  "email": "owner@example.com",
  "password": "supersecret123"
}
```

**Success — `200 OK`:**
```json
{
  "user": {
    "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "email": "owner@example.com",
    "fullName": "Jane Doe",
    "role": "MANAGER"
  },
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "accessTokenExpiresAt": "2026-08-04T16:45:00Z",
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshTokenExpiresAt": "2026-09-03T16:30:00Z"
}
```

**Failure — `401 Unauthorized`** (wrong email/password):
```json
{"errorCode":"AUTH_UNAUTHORIZED","message":"Authentication required"}
```
*(Note: this is the same fixed body every 401 on this API returns — it does not distinguish "wrong password" from "no token" in the response body itself. If Web needs a different message for a failed login form specifically, that's a product decision to raise with Backend, not something to infer from this response.)*

**Token lifetimes:** access token 15 minutes (default, `JWT_ACCESS_TTL_MINUTES`), refresh token 30 days (default, `JWT_REFRESH_TTL_DAYS`). Exchange a refresh token for a new pair via `POST /api/v1/auth/refresh` with `{"refreshToken": "..."}` — same response shape as login.

**Storage:** the backend does **not** set any cookie. Web is responsible for storing both tokens (session storage, memory, or an HttpOnly-cookie bridge that Web/Backend would need to build together — not implemented yet, see `ROJAN_Security_Backlog_v1.md`) and attaching the access token to every subsequent request.

---

## 2. Dashboard Flow

```
JWT
 |
 v
GET /api/v1/dashboard/insights
```

**Request:**
```http
GET /api/v1/dashboard/insights
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
```

No `salonId` or any other parameter — the backend resolves the caller's salon automatically from the token.

**Success — `200 OK`** (populated example):
```json
{
  "revenue": {
    "today": 150.00,
    "month": 3200.00,
    "growthRate": 18.50
  },
  "bookings": {
    "total": 42,
    "completed": 35,
    "cancelled": 4
  },
  "customers": {
    "newCustomers": 6,
    "returningCustomers": 12
  },
  "services": [
    { "name": "Haircut", "bookings": 20, "revenue": 2000.00 }
  ],
  "recommendations": [
    {
      "type": "REVENUE_GROWTH",
      "priority": "MEDIUM",
      "message": "درآمد شما نسبت به دوره قبل رشد داشته است."
    },
    {
      "type": "SERVICE_PERFORMANCE",
      "priority": "LOW",
      "message": "پرمخاطب‌ترین خدمت این ماه: Haircut"
    }
  ]
}
```

**Empty state — `200 OK`** (new salon, no bookings yet — render an empty-state UI, not an error):
```json
{
  "revenue": { "today": 0, "month": 0, "growthRate": 0 },
  "bookings": { "total": 0, "completed": 0, "cancelled": 0 },
  "customers": { "newCustomers": 0, "returningCustomers": 0 },
  "services": [],
  "recommendations": []
}
```

### Error cases

| Status | `errorCode` | Meaning | Suggested Web behavior |
|---|---|---|---|
| `401` | `AUTH_UNAUTHORIZED` | No/invalid/expired token | Redirect to login, or silently attempt `/api/v1/auth/refresh` first if a refresh token is still held |
| `404` | `SALON_NOT_FOUND` | Authenticated, but this user owns no salon | Show onboarding: "create your salon" flow, not an error page |
| `409` | `SALON_CONTEXT_REQUIRED` | This user owns more than one salon; backend can't pick one | No salon-picker exists server-side yet (see backlog) — treat as "unsupported for now" or contact Backend before building UI around it |
| `500` | `INTERNAL_ERROR` | Unhandled server error | Generic error state; log the `traceId` from the body for support correlation |

**401 body** (fixed, same on every endpoint):
```json
{"errorCode":"AUTH_UNAUTHORIZED","message":"Authentication required"}
```

**404/409/500 body** (shared `ApiError` shape, used everywhere else in the API):
```json
{
  "timestamp": "2026-08-04T16:00:00Z",
  "status": 404,
  "error": "Not Found",
  "errorCode": "SALON_NOT_FOUND",
  "message": "Salon not found: 3fa85f64-...",
  "path": "/api/v1/dashboard/insights",
  "traceId": "a1b2c3d4-..."
}
```

**Recommended dispatch logic:**
```
switch (response.status) {
  200: render dashboard (empty state if bookings.total === 0)
  401: refresh token, or redirect to login
  404: check errorCode === "SALON_NOT_FOUND" → onboarding
  409: check errorCode === "SALON_CONTEXT_REQUIRED" → unsupported-for-now state
  default: generic error, log traceId if present
}
```

---

## 3. Headers reference

| Header | When | Value |
|---|---|---|
| `Authorization` | Every request except `/api/v1/auth/**` and `/api/v1/public/**` | `Bearer <accessToken>` |
| `Content-Type` | Every request with a body | `application/json` |

No other custom headers are required or read by any endpoint covered here.

## 4. What Web should NOT assume

- No CORS policy is configured on the backend today. If Web's environment (dev or prod) runs on a different origin than the API, cross-origin requests will be blocked by the browser until Backend adds one — confirm deployment topology (same-origin via Nginx vs. separate domains) before integration testing.
- No cookie-based session exists. Bearer-token-in-header is the only supported auth transport right now.
- No rate limiting exists on `/api/v1/auth/login` — don't build a retry loop that could itself contribute to abuse.
