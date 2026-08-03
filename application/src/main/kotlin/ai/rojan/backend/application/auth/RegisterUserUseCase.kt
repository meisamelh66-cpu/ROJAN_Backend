package ai.rojan.backend.application.auth

import ai.rojan.backend.application.port.PasswordEncoderPort
import ai.rojan.backend.domain.common.EmailAlreadyRegisteredException
import ai.rojan.backend.domain.user.Email
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserRepository
import ai.rojan.backend.domain.user.UserRole

data class RegisterUserCommand(
    val email: String,
    val rawPassword: String,
    val fullName: String,
    val role: UserRole,
)

class RegisterUserUseCase(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoderPort,
) {
    fun execute(command: RegisterUserCommand): User {
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

    private companion object {
        const val MIN_PASSWORD_LENGTH = 8
    }
}
