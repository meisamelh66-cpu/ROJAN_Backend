package ai.rojan.backend.application.auth

import ai.rojan.backend.application.port.PasswordEncoderPort
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.EmailAlreadyRegisteredException
import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.common.RegisterRateLimitExceededException
import ai.rojan.backend.domain.common.SortDirection
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

    override fun findByRole(role: UserRole): List<User> = store.values.filter { it.role == role }

    /** Not exercised by this file's tests - a minimal, correct in-memory implementation only to satisfy the interface (Platform Management API Contract). */
    override fun findByRole(role: UserRole, pageRequest: PageRequest, search: String?, sortDirection: SortDirection): PageResult<User> {
        val matches = store.values
            .filter { it.role == role }
            .filter { search.isNullOrBlank() || it.fullName.contains(search, ignoreCase = true) || it.phoneNumber?.value?.contains(search) == true }
            .sortedBy { it.fullName }
            .let { if (sortDirection == SortDirection.DESC) it.reversed() else it }
        val fromIndex = (pageRequest.page * pageRequest.size).coerceIn(0, matches.size)
        val toIndex = (fromIndex + pageRequest.size).coerceIn(fromIndex, matches.size)
        return PageResult(content = matches.subList(fromIndex, toIndex), page = pageRequest.page, size = pageRequest.size, totalElements = matches.size.toLong())
    }
}

private class PlainTextPasswordEncoder : PasswordEncoderPort {
    override fun encode(rawPassword: String): String = "hashed:$rawPassword"

    override fun matches(rawPassword: String, encodedPassword: String): Boolean =
        encodedPassword == "hashed:$rawPassword"
}

class RegisterUserUseCaseTest {

    private val userRepository = InMemoryUserRepository()
    private val useCase = RegisterUserUseCase(userRepository, PlainTextPasswordEncoder(), RecordingRateLimiter(), testAuthRateLimitPolicy)

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

    @Test
    fun `exceeding the per-IP rate limit throws before touching the repository`() {
        val limitedUseCase = RegisterUserUseCase(
            InMemoryUserRepository(),
            PlainTextPasswordEncoder(),
            RecordingRateLimiter(deniedKeyPrefixes = setOf("auth:register:ip:")),
            testAuthRateLimitPolicy,
        )

        assertThrows<RegisterRateLimitExceededException> {
            limitedUseCase.execute(RegisterUserCommand("new@example.com", "supersecret", "New", UserRole.CUSTOMER, callerIp = "203.0.113.5"))
        }
    }

    @Test
    fun `a null caller IP skips the rate limit check rather than throwing`() {
        val user = useCase.execute(RegisterUserCommand("nolimit@example.com", "supersecret", "No Limit", UserRole.CUSTOMER, callerIp = null))

        assertEquals("nolimit@example.com", user.email?.value)
    }

    @Test
    fun `rate limit is keyed by caller IP, not by email`() {
        val rateLimiter = RecordingRateLimiter()
        val trackedUseCase = RegisterUserUseCase(InMemoryUserRepository(), PlainTextPasswordEncoder(), rateLimiter, testAuthRateLimitPolicy)

        trackedUseCase.execute(RegisterUserCommand("tracked@example.com", "supersecret", "Tracked", UserRole.CUSTOMER, callerIp = "203.0.113.5"))

        assertTrue(rateLimiter.consumedKeys.contains("auth:register:ip:203.0.113.5"))
    }
}
