package ai.rojan.backend.application.port

import ai.rojan.backend.domain.user.User
import java.time.Instant

data class IssuedToken(
    val token: String,
    val expiresAt: Instant,
    /** Refresh Token Rotation (`BACKEND_REFRESH_TOKEN_SECURITY_PLAN.md`): the same id [TokenSubject.jti] will report back when this exact token is later presented - returned here so an issuing use case can register it with [RefreshTokenStorePort] without re-parsing the token it just created. */
    val jti: String,
)

enum class TokenType {
    ACCESS,
    REFRESH,
}

data class TokenSubject(
    val userId: String,
    /** Mobile-First Authentication Phase 1: nullable — a phone-only account's tokens carry no email claim. [userId] is the only identity anchor any caller should rely on being present (see `CurrentUserResolver`). */
    val email: String?,
    val role: String,
    val type: TokenType,
    /** Refresh Token Rotation (`BACKEND_REFRESH_TOKEN_SECURITY_PLAN.md`): every token's own unique id - meaningless for [TokenType.ACCESS] (nothing consults it there), the reuse-detection anchor for [TokenType.REFRESH]. */
    val jti: String,
    /** Only ever set on a [TokenType.REFRESH] token, and only when it carries the family-rotation claim - null for every [TokenType.ACCESS] token (which never has one) and for a legacy refresh token issued before this scheme existed (see [RefreshTokenUseCase]'s backward-compatible migration path). */
    val familyId: String? = null,
)

/**
 * Output port for issuing and validating bearer tokens. Implemented in
 * infrastructure (JWT today; swappable without touching use cases).
 */
interface TokenProviderPort {
    fun generateAccessToken(user: User): IssuedToken

    /** [familyId] is carried forward unchanged through every rotation of the same refresh-token family - a fresh login/OTP verification passes a newly-generated one; [RefreshTokenUseCase] passes the family's existing one when rotating. */
    fun generateRefreshToken(user: User, familyId: String): IssuedToken

    /**
     * Validates signature, issuer, and expiry only — callers that care about
     * access-vs-refresh must check [TokenSubject.type] themselves. Kept this
     * way (rather than two separate validate methods) so both call sites —
     * the request-authenticating filter and the refresh use case — share one
     * signature-verification path.
     *
     * @throws ai.rojan.backend.domain.common.InvalidTokenException if the token is malformed, expired, or unsigned by us.
     */
    fun validateAndExtractSubject(token: String): TokenSubject
}
