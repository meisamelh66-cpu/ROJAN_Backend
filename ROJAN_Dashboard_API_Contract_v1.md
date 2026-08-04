# ROJAN Dashboard API Contract v1

`GET /api/v1/dashboard/insights`

Status: current implementation, verified against source. Consumed by ROJAN Web's AI Insights Card.

---

## Endpoint

```
GET /api/v1/dashboard/insights
```

No path or query parameters. No request body.

## Authentication

**Strategy: Bearer JWT** (current implementation — see "Cookie migration" note below).

```
Authorization: Bearer <accessToken>
```

- Token obtained from `POST /api/v1/auth/login` or `POST /api/v1/auth/refresh`.
- Access tokens expire in `JWT_ACCESS_TTL_MINUTES` (default 15 minutes) — Web must refresh proactively or handle a 401 mid-session by calling `/api/v1/auth/refresh` with the stored refresh token.
- No cookie-based session exists today. If/when the backend adopts the HttpOnly-cookie bridge described in the architecture report, this section will change; until then, Web must hold and attach the bearer token itself.

## Tenant resolution

No `salonId` is passed by the client. The backend resolves it server-side from the authenticated user:

```
JWT -> userId -> SalonRepository.findByOwnerId(userId) -> salon
```

| Salons owned by caller | Result |
|---|---|
| 0 | `404 SALON_NOT_FOUND` |
| 1 | Insights computed for that salon |
| 2+ | `409 SALON_CONTEXT_REQUIRED` |

## Response — `200 OK`

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
    }
  ]
}
```

### Field reference

| Path | Type | Presence |
|---|---|---|
| `revenue.today` | number (decimal) | always present |
| `revenue.month` | number (decimal) | always present |
| `revenue.growthRate` | number (decimal, %) | always present; 0 if no comparable prior-month data |
| `bookings.total` | integer | always present; this-month count, all statuses |
| `bookings.completed` | integer | always present |
| `bookings.cancelled` | integer | always present |
| `customers.newCustomers` | integer | always present |
| `customers.returningCustomers` | integer | always present |
| `services` | array | always present; may be `[]` |
| `services[].name` | string | required within each item |
| `services[].bookings` | integer | required within each item |
| `services[].revenue` | number (decimal) | required within each item |
| `recommendations` | array | always present; may be `[]` |
| `recommendations[].type` | string enum | see engine audit — e.g. `REVENUE_GROWTH`, `REVENUE_DECLINE`, `BOOKING_GROWTH`, `BOOKING_DECLINE`, `CANCELLATION_RATE`, `CUSTOMER_RETENTION_LOW`, `CUSTOMER_RETENTION_HIGH`, `SERVICE_PERFORMANCE` |
| `recommendations[].priority` | string enum | `LOW` \| `MEDIUM` \| `HIGH` |
| `recommendations[].message` | string | human-readable, currently Persian |

**No optional/nullable fields exist in this response.** Every key above is always present on a `200`.

## Empty state (Web must handle explicitly)

A salon with no bookings this month still returns `200`, not an error:
```json
{
  "revenue": { "today": 0, "month": 0, "growthRate": 0 },
  "bookings": { "total": 0, "completed": 0, "cancelled": 0 },
  "customers": { "newCustomers": 0, "returningCustomers": 0 },
  "services": [],
  "recommendations": []
}
```
Web should render its empty/zero-state UI when `bookings.total === 0` and/or `recommendations.length === 0` — this is not an error condition and must not be treated as one.

## Loading state

Not modeled by the API — this is a synchronous request/response endpoint with no streaming or polling semantics. Web is responsible for its own loading indicator between request and response.

## Error responses

All error bodies (except 401 — see below) share this shape:

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

| Status | `errorCode` | Cause | Body |
|---|---|---|---|
| `401 Unauthorized` | *(none — see note)* | Missing, malformed, or expired bearer token | **Empty body**, `Content-Length: 0`. This is produced by the Spring Security filter chain before the request ever reaches application code — it does **not** carry an `ApiError`/`errorCode`. Web must treat any 401 on this endpoint as "re-authenticate," not attempt to parse a body. |
| `404 Not Found` | `SALON_NOT_FOUND` | Caller does not own any salon | `ApiError` JSON |
| `409 Conflict` | `SALON_CONTEXT_REQUIRED` | Caller owns more than one salon; server cannot pick one implicitly | `ApiError` JSON |
| `500 Internal Server Error` | `INTERNAL_ERROR` | Unhandled server error | `ApiError` JSON, generic message only (no stack trace/detail leaked) |

`errorCode` is a new, additive field as of this revision — safe for Web to ignore if not yet consumed, but recommended for branching logic instead of matching on `message` (free text, not guaranteed stable) or `status` alone (multiple distinct errors can share one status elsewhere in the API, though not on this endpoint today).

## Recommended Web integration pattern

```
if status == 401: redirect to login / attempt token refresh
elif status == 404 (errorCode SALON_NOT_FOUND): show "create your salon" onboarding state
elif status == 409 (errorCode SALON_CONTEXT_REQUIRED): show a salon-picker (not yet supported server-side — see architecture report's open decisions) or contact support
elif status == 200: render dashboard; if bookings.total == 0, render empty state instead of charts
else: generic error state, log traceId for support correlation
```
