package ai.rojan.backend.application.auth

import ai.rojan.backend.application.port.PasswordEncoderPort
import ai.rojan.backend.application.port.RateLimiterPort
import ai.rojan.backend.domain.common.EmailAlreadyRegisteredException
import ai.rojan.backend.domain.common.RegisterRateLimitExceededException
import ai.rojan.backend.domain.user.Email
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserRepository
import ai.rojan.backend.domain.user.UserRole
import java.time.Duration

/** [callerIp] is optional, same reasoning as [AuthenticateUserCommand.callerIp]. */
data class RegisterUserCommand(
    val email: String,
    val rawPassword: String,
    val fullName: String,
    val role: UserRole,
    val callerIp: String? = null,
)

class RegisterUserUseCase(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoderPort,
    private val rateLimiter: RateLimiterPort,
    private val policy: AuthRateLimitPolicy,
) {
    fun execute(command: RegisterUserCommand): User {
        enforceRateLimit(command.callerIp)

        val email = Email(command.email.trim().lowercase())
        if (userRepository.existsByEmail(email)) {
            throw EmailAlreadyRegisteredException(email.value)
        }
        require(command.rawPassword.length >= MIN_PASSWORD_LENGTH) {
            "Password must be at least $MIN_PASSWORD_LENGTH characters"
        }

        val user = User.register(
            email = email,
            passwordHash = passwordEncoder.encode(command.rawPassword),
            fullName = command.fullName,
            role = command.role,
        )
        return userRepository.save(user)
    }

    /** No per-email key here - unlike login, an unregistered email has no prior identity to key against, and [EmailAlreadyRegisteredException] already rejects a repeat email regardless of rate. Per-IP is the meaningful control for registration spam. */
    private fun enforceRateLimit(callerIp: String?) {
        if (callerIp == null) return

        val ipKey = "auth:register:ip:$callerIp"
        if (!rateLimiter.tryConsume(ipKey, policy.registerLimitPerIpWindow, Duration.ofSeconds(policy.registerWindowSeconds))) {
            throw RegisterRateLimitExceededException(callerIp)
        }
    }

    private companion object {
        const val MIN_PASSWORD_LENGTH = 8
    }
}
