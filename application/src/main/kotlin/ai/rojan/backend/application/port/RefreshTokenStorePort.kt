package ai.rojan.backend.application.port

import java.time.Duration

/**
 * Output port for refresh-token-family rotation/reuse-detection state (see
 * `BACKEND_REFRESH_TOKEN_SECURITY_PLAN.md`) - the one part of the refresh flow that needs
 * centralized (Redis) coordination. Deliberately never consulted from
 * `infrastructure.security.JwtAuthenticationFilter` (the access-token-validating path every
 * ordinary API request goes through) - only `RefreshTokenUseCase`'s comparatively rare
 * `/auth/refresh` call pays this cost, keeping normal request authentication Redis-free and
 * horizontally-scalable per-instance.
 *
 * One key per family, holding whichever token is currently the valid one to present next - not
 * one key per issued token. A family starts at login/OTP verification (a fresh, random family id)
 * and is carried forward, unchanged, through every subsequent rotation; [currentJti] mismatching
 * the jti a caller actually presented is the reuse signal ([RefreshTokenUseCase] acts on it).
 */
interface RefreshTokenStorePort {
    /** Records [jti] as the currently-active token for [familyId] (superseding whatever was there before), expiring after [ttl] - starts a brand-new family (login/OTP) or rotates an existing one (refresh). */
    fun activate(familyId: String, jti: String, ttl: Duration)

    /** The currently-active jti for [familyId], or null if the family was never activated, has expired, or was revoked. */
    fun currentJti(familyId: String): String?

    /** Revokes every token in [familyId] - the next refresh attempt against it, with any jti, fails closed. */
    fun revokeFamily(familyId: String)
}
