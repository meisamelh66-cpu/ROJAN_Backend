# ROJAN Backend — Auth API Contract

Base path: `/api/v1`. All requests/responses are JSON
(`Content-Type: application/json`). Full machine-readable spec is served
live at `/v3/api-docs` (Swagger UI at `/swagger-ui/index.html`) — this
document is the human-readable version of the same contract, kept in sync
by hand.

## Authentication model

Two JWTs are issued together at login/refresh: a short-lived **access
token** (default 15 min, `rojan.security.jwt.access-token-ttl-minutes`) for
calling the API, and a longer-lived **refresh token** (default 30 days,
`rojan.security.jwt.refresh-token-ttl-days`) for obtaining a new pair. Each
token embeds its own type and is only valid for its own purpose — a refresh
token sent to a protected endpoint is rejected (`401`), and an access token
sent to `/auth/refresh` is rejected (`401`).

Protected endpoints require `Authorization: Bearer <accessToken>`.

## `POST /api/v1/auth/register`

Creates a new account. Public.

**Request**
```json
{
  "email": "jane@example.com",
  "password": "at-least-8-chars",
  "fullName": "Jane Doe",
  "role": "CUSTOMER"
}
```
| Field | Type | Constraints |
|---|---|---|
| `email` | string | required, valid email |
| `password` | string | required, 8–128 chars |
| `fullName` | string | required, ≤255 chars |
| `role` | enum | required — `CUSTOMER` \| `MANAGER` \| `SPECIALIST` |

**Response — `201 Created`**
```json
{
  "id": "5d0c5f42-ae83-4944-bb6b-8581da6d529d",
  "email": "jane@example.com",
  "fullName": "Jane Doe",
  "role": "CUSTOMER"
}
```

**Errors**: `409` email already registered · `400` validation failure (bad
email format, short password, etc.)

## `POST /api/v1/auth/login`

Exchanges credentials for a token pair. Public.

**Request**
```json
{ "email": "jane@example.com", "password": "at-least-8-chars" }
```

**Response — `200 OK`**
```json
{
  "user": { "id": "5d0c...", "email": "jane@example.com", "fullName": "Jane Doe", "role": "CUSTOMER" },
  "accessToken": "eyJhbGciOiJIUzUxMiJ9...",
  "accessTokenExpiresAt": "2026-08-01T21:59:29.408706300Z",
  "refreshToken": "eyJhbGciOiJIUzUxMiJ9...",
  "refreshTokenExpiresAt": "2026-08-31T21:44:29.585980400Z"
}
```

**Errors**: `401` wrong email/password · `403` account deactivated

## `POST /api/v1/auth/refresh`

Exchanges a valid, unexpired refresh token for a **new** access/refresh
pair (rotation — the old refresh token is not reusable-tracked yet, see
Known gaps below). Public (the refresh token itself is the credential).

**Request**
```json
{ "refreshToken": "eyJhbGciOiJIUzUxMiJ9..." }
```

**Response — `200 OK`**: same `AuthResponse` shape as `/login`.

**Errors**: `401` token invalid, expired, or not actually a refresh token
(e.g. an access token was sent instead) · `403` account deactivated · `404`
the account behind the token no longer exists

## `GET /api/v1/users/me`

Returns the authenticated caller. Requires a valid **access** token.

**Response — `200 OK`**
```json
{ "id": "5d0c...", "email": "jane@example.com", "fullName": "Jane Doe", "role": "CUSTOMER" }
```

**Errors**: `401` missing/invalid/wrong-type bearer token

## Error shape

Every non-2xx response from a handled exception (not Spring Security's own
`401` denial for missing/invalid credentials, which has no body) has this
shape:

```json
{
  "timestamp": "2026-08-01T21:44:29.585Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "Invalid email or password",
  "path": "/api/v1/auth/login"
}
```

## Known gaps (not in scope for this milestone)

- No refresh-token revocation/rotation tracking — a stolen refresh token
  remains valid until it expires. Would need a persisted token/session
  record to revoke.
- No rate limiting on `/auth/*`.
