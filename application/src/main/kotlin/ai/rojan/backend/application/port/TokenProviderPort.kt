package ai.rojan.backend.application.port

import ai.rojan.backend.domain.user.User
import java.time.Instant

data class IssuedToken(
    val token: String,
    val expiresAt: Instant,
)

data class TokenSubject(
    val userId: String,
    val email: String,
    val role: String,
)

/**
 * Output port for issuing and validating bearer tokens. Implemented in
 * infrastructure (JWT today; swappable without touching use cases).
 */
interface TokenProviderPort {
    fun generateAccessToken(user: User): IssuedToken
    fun generateRefreshToken(user: User): IssuedToken

    /** @throws ai.rojan.backend.domain.common.InvalidTokenException if the token is malformed, expired, or unsigned by us. */
    fun validateAndExtractSubject(token: String): TokenSubject
}
