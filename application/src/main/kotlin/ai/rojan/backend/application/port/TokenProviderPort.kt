package ai.rojan.backend.application.port

import ai.rojan.backend.domain.user.User
import java.time.Instant

data class IssuedToken(
    val token: String,
    val expiresAt: Instant,
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
)

/**
 * Output port for issuing and validating bearer tokens. Implemented in
 * infrastructure (JWT today; swappable without touching use cases).
 */
interface TokenProviderPort {
    fun generateAccessToken(user: User): IssuedToken
    fun generateRefreshToken(user: User): IssuedToken

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
