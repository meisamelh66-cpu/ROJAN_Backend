# ROJAN Architecture Compliance Report v1

Scope: `ROJAN_Backend` only (Phase 1 — Identity Layer & Tenant Model compliance). No code was changed to produce this report.

---

## Identity Layer — Approved Flow

```
User Identity -> JWT Authentication -> Tenant Resolution -> Resource Authorization
```

| Stage | Implementation | Verified |
|---|---|---|
| User Identity | `User` aggregate (`domain/user/User.kt`), email + BCrypt password hash | ✅ |
| JWT Authentication | `JwtAuthenticationFilter` validates every request's `Authorization: Bearer` header before Spring Security's authorization stage runs | ✅ |
| Tenant Resolution | `GetDashboardInsightsUseCase`: `SalonRepository.findByOwnerId(callerId)` — resolved fresh on every request, not cached in the token | ✅ |
| Resource Authorization | Ownership comparison (`salon.ownerId == callerId`), enforced per-request in application-layer use cases | ✅ |

This is exactly the approved flow, end to end, with no shortcuts (e.g., no salon context cached in the session, no role-based bypass).

## Tenant Model — Approved Rule

```
User -> Salon.ownerId -> Dashboard Context
Salon Owner = authenticated User where User.id == Salon.ownerId
```

**Verified compliant:**
- `Salon.ownerId: UserId` (`domain/salon/Salon.kt`) is the sole ownership reference, set once at creation, never reassigned.
- `SalonRepository.findByOwnerId(ownerId): List<Salon>` is the only ownership lookup — no parallel "membership" or "staff" table.
- `GetDashboardInsightsUseCase` resolves dashboard context by this rule alone: 0 salons → 404, 1 → resolved, 2+ → 409.

## Confirmations Required by This Phase

### 1. Ownership is resource-based ✅

Confirmed. Access to a salon's resources (including the dashboard) is granted by comparing `callerId` against `Salon.ownerId` — a data relationship, not a permission flag or role.

### 2. No dependency on `ROLE_OWNER` ✅

Confirmed by direct source inspection: `UserRole` (`domain/user/User.kt`) has exactly three values — `CUSTOMER`, `MANAGER`, `SPECIALIST`. **No `OWNER` value exists, was never added.** Codebase-wide search for `@PreAuthorize`, `@Secured`, `hasRole`, `hasAuthority`, `@RolesAllowed` returns zero matches — no endpoint anywhere is gated by a Spring Security role check. "Owner" is purely the `Salon.ownerId == User.id` relationship; any `UserRole` value can create a salon and become an owner (`CreateSalonUseCase` performs no role check — noted as an open architecture question in prior audits, not something this phase was asked to resolve).

### 3. `salonId` is NOT stored inside the JWT ✅

Confirmed by direct source inspection of `JwtTokenProvider.kt` (`infrastructure/security/`). Full claim set on both access and refresh tokens:
```
sub (userId), email, role, type (ACCESS|REFRESH), iss, iat, exp, jti
```
No `salonId`, `tenantId`, or any salon-related claim exists anywhere in token issuance (`issue()`) or validation (`validateAndExtractSubject()`). Tenant context is resolved dynamically, per-request, exactly as the approved architecture requires — never cached in the token.

## Verdict

**PASS on all three compliance checks.** No architecture drift found. No code change was needed or made for this phase — this is unchanged from the prior architecture audit and implementation report, re-confirmed here as part of this execution order's controlled review.
