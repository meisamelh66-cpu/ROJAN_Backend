package ai.rojan.backend.application.auth

import ai.rojan.backend.application.port.PasswordEncoderPort
import ai.rojan.backend.application.port.RateLimiterPort
import ai.rojan.backend.application.port.TokenProviderPort
import ai.rojan.backend.domain.common.InactiveUserException
import ai.rojan.backend.domain.common.InvalidCredentialsException
import ai.rojan.backend.domain.common.LoginRateLimitExceededException
import ai.rojan.backend.domain.user.Email
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserRepository
import java.time.Duration
import java.time.Instant

/** [callerIp] is optional (mirrors [RequestOtpCommand]'s own reasoning) so a test/internal caller with no HTTP context can still exercise this use case - `AuthController` always supplies the real one. */
data class AuthenticateUserCommand(
    val email: String,
    val rawPassword: String,
    val callerIp: String? = null,
)

data class AuthenticationResult(
    val user: User,
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
)

class AuthenticateUserUseCase(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoderPort,
    private val tokenProvider: TokenProviderPort,
    private val rateLimiter: RateLimiterPort,
    private val policy: AuthRateLimitPolicy,
) {
    fun execute(command: AuthenticateUserCommand): AuthenticationResult {
        val email = Email(command.email.trim().lowercase())
        enforceRateLimit(email, command.callerIp)

        val user = userRepository.findByEmail(email) ?: throw InvalidCredentialsException()

        // Mobile-First Authentication Phase 1: a phone-only account (registered via
        // OTP) has no passwordHash at all — email/password login must reject it
        // cleanly as "invalid credentials," not NPE, since it was never a valid
        // credential pair for that account in the first place.
        val passwordHash = user.passwordHash ?: throw InvalidCredentialsException()
        if (!passwordEncoder.matches(command.rawPassword, passwordHash)) {
            throw InvalidCredentialsException()
        }
        if (!user.active) {
            throw InactiveUserException(user.id.value.toString())
        }

        return issueTokens(user)
    }

    /**
     * Keyed by the normalized *submitted* email, regardless of whether it
     * belongs to a real account - deciding the rate-limit key from a DB
     * lookup result would mean a nonexistent-email probe and a
     * wrong-password probe consume different budgets, which is exactly the
     * kind of enumeration signal [InvalidCredentialsException]'s own single
     * "invalid credentials" message (used for both cases) already avoids
     * elsewhere in this class. Same "consume every applicable budget
     * regardless of which one is already exhausted" reasoning as
     * [RequestOtpUseCase.enforceRateLimits].
     */
    private fun enforceRateLimit(email: Email, callerIp: String?) {
        val emailKey = "auth:login:email:${email.value}"
        val withinEmailWindow = rateLimiter.tryConsume(emailKey, policy.loginLimitPerEmailWindow, Duration.ofSeconds(policy.loginWindowSeconds))

        val withinIpWindow = callerIp?.let {
            rateLimiter.tryConsume("auth:login:ip:$it", policy.loginLimitPerIpWindow, Duration.ofSeconds(policy.loginWindowSeconds))
        } ?: true

        if (!withinEmailWindow || !withinIpWindow) {
            throw LoginRateLimitExceededException(email.value)
        }
    }

    private fun issueTokens(user: User): AuthenticationResult {
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
}
