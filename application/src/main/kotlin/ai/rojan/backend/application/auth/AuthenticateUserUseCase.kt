package ai.rojan.backend.application.auth

import ai.rojan.backend.application.port.PasswordEncoderPort
import ai.rojan.backend.application.port.TokenProviderPort
import ai.rojan.backend.domain.common.InactiveUserException
import ai.rojan.backend.domain.common.InvalidCredentialsException
import ai.rojan.backend.domain.user.Email
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserRepository
import java.time.Instant

data class AuthenticateUserCommand(
    val email: String,
    val rawPassword: String,
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
) {
    fun execute(command: AuthenticateUserCommand): AuthenticationResult {
        val email = Email(command.email.trim().lowercase())
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
