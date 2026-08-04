package ai.rojan.backend.application.auth

import ai.rojan.backend.application.port.PasswordEncoderPort
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.EmailAlreadyRegisteredException
import ai.rojan.backend.domain.user.Email
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository
import ai.rojan.backend.domain.user.UserRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

private class InMemoryUserRepository : UserRepository {
    private val store = mutableMapOf<UserId, User>()

    override fun save(user: User): User {
        store[user.id] = user
        return user
    }

    override fun findById(id: UserId): User? = store[id]

    override fun findByEmail(email: Email): User? = store.values.find { it.email == email }

    override fun existsByEmail(email: Email): Boolean = store.values.any { it.email == email }

    override fun findByPhoneNumber(phoneNumber: PhoneNumber): User? = store.values.find { it.phoneNumber == phoneNumber }

    override fun existsByPhoneNumber(phoneNumber: PhoneNumber): Boolean = store.values.any { it.phoneNumber == phoneNumber }
}

private class PlainTextPasswordEncoder : PasswordEncoderPort {
    override fun encode(rawPassword: String): String = "hashed:$rawPassword"

    override fun matches(rawPassword: String, encodedPassword: String): Boolean =
        encodedPassword == "hashed:$rawPassword"
}

class RegisterUserUseCaseTest {

    private val userRepository = InMemoryUserRepository()
    private val useCase = RegisterUserUseCase(userRepository, PlainTextPasswordEncoder())

    @Test
    fun `registers a new user with normalized email and hashed password`() {
        val user = useCase.execute(
            RegisterUserCommand(
                email = "New.User@Example.com",
                rawPassword = "supersecret",
                fullName = "New User",
                role = UserRole.CUSTOMER,
            ),
        )

        assertEquals("new.user@example.com", user.email?.value)
        assertEquals("hashed:supersecret", user.passwordHash)
        assertTrue(userRepository.existsByEmail(Email("new.user@example.com")))
    }

    @Test
    fun `rejects a duplicate email`() {
        useCase.execute(RegisterUserCommand("dup@example.com", "supersecret", "First", UserRole.CUSTOMER))

        assertThrows<EmailAlreadyRegisteredException> {
            useCase.execute(RegisterUserCommand("dup@example.com", "anotherpass", "Second", UserRole.CUSTOMER))
        }
    }

    @Test
    fun `rejects passwords shorter than the minimum length`() {
        assertThrows<IllegalArgumentException> {
            useCase.execute(RegisterUserCommand("short@example.com", "short", "Name", UserRole.CUSTOMER))
        }
    }
}
