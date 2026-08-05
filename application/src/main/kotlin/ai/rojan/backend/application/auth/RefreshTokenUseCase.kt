package ai.rojan.backend.application.auth

import ai.rojan.backend.application.port.RateLimiterPort
import ai.rojan.backend.application.port.TokenProviderPort
import ai.rojan.backend.application.port.TokenType
import ai.rojan.backend.domain.common.InactiveUserException
import ai.rojan.backend.domain.common.InvalidTokenException
import ai.rojan.backend.domain.common.RefreshRateLimitExceededException
import ai.rojan.backend.domain.common.UserNotFoundException
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository
import java.time.Duration
import java.util.UUID

/** [callerIp] is optional, same reasoning as [AuthenticateUserCommand.callerIp]. */
data class RefreshTokenCommand(val refreshToken: String, val callerIp: String? = null)

class RefreshTokenUseCase(
    private val userRepository: UserRepository,
    private val tokenProvider: TokenProviderPort,
    private val rateLimiter: RateLimiterPort,
    private val policy: AuthRateLimitPolicy,
) {
    fun execute(command: RefreshTokenCommand): AuthenticationResult {
        enforceRateLimit(command.callerIp)

        val subject = tokenProvider.validateAndExtractSubject(command.refreshToken)
        if (subject.type != TokenType.REFRESH) {
            // An access token is signed the same way — reject it here rather than
            // letting it double as a refresh credential.
            throw InvalidTokenException()
        }
        val userId = runCatching { UUID.fromString(subject.userId) }
            .getOrElse { throw InvalidTokenException() }

        val user: User = userRepository.findById(UserId(userId))
            ?: throw UserNotFoundException(subject.userId)
        if (!user.active) {
            throw InactiveUserException(user.id.value.toString())
        }

        val accessToken = tokenProvider.generateAccessToken(user)
        val refreshToken = tokenProvider.generateRefreshToken(user)
        return AuthenticationResult(
            user = user,
            accessToken = accessToken.token,
            accessTokenExpiresAt = accessToken.expiresAt,
            refreshToken = refreshToken.token,
            refreshTokenExpiresAt = refreshToken.expiresAt,
        )
    }

    /** Keyed by caller IP only - unlike login, there is no stable pre-validation identity to key by (the token itself might be garbage), and validating it first would mean a flood of malformed tokens never gets rate-limited at all. */
    private fun enforceRateLimit(callerIp: String?) {
        if (callerIp == null) return

        val ipKey = "auth:refresh:ip:$callerIp"
        if (!rateLimiter.tryConsume(ipKey, policy.refreshLimitPerIpWindow, Duration.ofSeconds(policy.refreshWindowSeconds))) {
            throw RefreshRateLimitExceededException(callerIp)
        }
    }
}
