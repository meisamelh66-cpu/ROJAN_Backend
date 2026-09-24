package ai.rojan.backend.application.auth

import ai.rojan.backend.application.port.IssuedToken
import ai.rojan.backend.application.port.PasswordEncoderPort
import ai.rojan.backend.application.port.TokenProviderPort
import ai.rojan.backend.application.port.TokenSubject
import ai.rojan.backend.application.port.TokenType
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.InvalidCredentialsException
import ai.rojan.backend.domain.common.LoginRateLimitExceededException
import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
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
import java.time.Instant

private class SingleUserRepository(private val user: User) : UserRepository {
    override fun save(user: User): User = user
    override fun findById(id: UserId): User? = user.takeIf { it.id == id }
    override fun findByEmail(email: Email): User? = user.takeIf { it.email == email }
    override fun existsByEmail(email: Email): Boolean = user.email == email
    override fun findByPhoneNumber(phoneNumber: PhoneNumber): User? = user.takeIf { it.phoneNumber == phoneNumber }
    override fun existsByPhoneNumber(phoneNumber: PhoneNumber): Boolean = user.phoneNumber == phoneNumber
    override fun findByRole(role: UserRole): List<User> = listOfNotNull(user.takeIf { it.role == role })

    /** Not exercised by this file's tests - a minimal, correct in-memory implementation only to satisfy the interface (Platform Management API Contract). */
    override fun findByRole(role: UserRole, pageRequest: PageRequest, search: String?, sortDirection: SortDirection): PageResult<User> {
        val matches = findByRole(role)
        return PageResult(content = matches.take(pageRequest.size), page = pageRequest.page, size = pageRequest.size, totalElements = matches.size.toLong())
    }
}

private class MatchingPasswordEncoder(private val correctRawPassword: String) : PasswordEncoderPort {
    override fun encode(rawPassword: String): String = rawPassword
    override fun matches(rawPassword: String, encodedPassword: String): Boolean =
        rawPassword == correctRawPassword
}

private class FakeTokenProvider : TokenProviderPort {
    override fun generateAccessToken(user: User) =
        IssuedToken("access-${user.id.value}", Instant.now().plusSeconds(900), jti = "access-jti-${user.id.value}")

    override fun generateRefreshToken(user: User, familyId: String) =
        IssuedToken("refresh-${user.id.value}", Instant.now().plusSeconds(2_592_000), jti = "refresh-jti-${user.id.value}")

    override fun validateAndExtractSubject(token: String) =
        TokenSubject(userId = token.substringAfter("-"), email = "", role = "", type = TokenType.ACCESS, jti = "jti-${token.substringAfter("-")}")
}

class AuthenticateUserUseCaseTest {

    private val user = User.register(
        email = Email("member@example.com"),
        passwordHash = "irrelevant-because-fake-encoder-ignores-it",
        fullName = "Member",
        role = UserRole.CUSTOMER,
    )

    private val refreshTokenStore = RecordingRefreshTokenStore()

    private val useCase = AuthenticateUserUseCase(
        userRepository = SingleUserRepository(user),
        passwordEncoder = MatchingPasswordEncoder(correctRawPassword = "correct-password"),
        tokenProvider = FakeTokenProvider(),
        rateLimiter = RecordingRateLimiter(),
        policy = testAuthRateLimitPolicy,
        refreshTokenStore = refreshTokenStore,
    )

    @Test
    fun `issues a token pair for valid credentials`() {
        val result = useCase.execute(
            AuthenticateUserCommand(email = "member@example.com", rawPassword = "correct-password"),
        )

        assertEquals("access-${user.id.value}", result.accessToken)
        assertEquals("refresh-${user.id.value}", result.refreshToken)
    }

    @Test
    fun `activates a brand-new refresh-token family in the store on every fresh login`() {
        useCase.execute(AuthenticateUserCommand(email = "member@example.com", rawPassword = "correct-password"))

        assertEquals(1, refreshTokenStore.activations.size)
    }

    @Test
    fun `rejects the wrong password`() {
        assertThrows<InvalidCredentialsException> {
            useCase.execute(AuthenticateUserCommand(email = "member@example.com", rawPassword = "wrong-password"))
        }
    }

    @Test
    fun `rejects an unknown email`() {
        assertThrows<InvalidCredentialsException> {
            useCase.execute(AuthenticateUserCommand(email = "nobody@example.com", rawPassword = "correct-password"))
        }
    }

    @Test
    fun `exceeding the per-email rate limit throws even with correct credentials`() {
        val limitedUseCase = AuthenticateUserUseCase(
            userRepository = SingleUserRepository(user),
            passwordEncoder = MatchingPasswordEncoder(correctRawPassword = "correct-password"),
            tokenProvider = FakeTokenProvider(),
            rateLimiter = RecordingRateLimiter(deniedKeyPrefixes = setOf("auth:login:email:")),
            policy = testAuthRateLimitPolicy,
            refreshTokenStore = RecordingRefreshTokenStore(),
        )

        assertThrows<LoginRateLimitExceededException> {
            limitedUseCase.execute(AuthenticateUserCommand(email = "member@example.com", rawPassword = "correct-password"))
        }
    }

    @Test
    fun `exceeding the per-IP rate limit throws even with correct credentials`() {
        val limitedUseCase = AuthenticateUserUseCase(
            userRepository = SingleUserRepository(user),
            passwordEncoder = MatchingPasswordEncoder(correctRawPassword = "correct-password"),
            tokenProvider = FakeTokenProvider(),
            rateLimiter = RecordingRateLimiter(deniedKeyPrefixes = setOf("auth:login:ip:")),
            policy = testAuthRateLimitPolicy,
            refreshTokenStore = RecordingRefreshTokenStore(),
        )

        assertThrows<LoginRateLimitExceededException> {
            limitedUseCase.execute(AuthenticateUserCommand(email = "member@example.com", rawPassword = "correct-password", callerIp = "203.0.113.5"))
        }
    }

    @Test
    fun `a null caller IP does not skip the per-email rate limit check`() {
        val limitedUseCase = AuthenticateUserUseCase(
            userRepository = SingleUserRepository(user),
            passwordEncoder = MatchingPasswordEncoder(correctRawPassword = "correct-password"),
            tokenProvider = FakeTokenProvider(),
            rateLimiter = RecordingRateLimiter(deniedKeyPrefixes = setOf("auth:login:email:")),
            policy = testAuthRateLimitPolicy,
            refreshTokenStore = RecordingRefreshTokenStore(),
        )

        assertThrows<LoginRateLimitExceededException> {
            limitedUseCase.execute(AuthenticateUserCommand(email = "member@example.com", rawPassword = "correct-password", callerIp = null))
        }
    }

    @Test
    fun `rate limit is keyed by the submitted email even for an unknown account`() {
        val rateLimiter = RecordingRateLimiter()
        val trackedUseCase = AuthenticateUserUseCase(
            userRepository = SingleUserRepository(user),
            passwordEncoder = MatchingPasswordEncoder(correctRawPassword = "correct-password"),
            tokenProvider = FakeTokenProvider(),
            rateLimiter = rateLimiter,
            policy = testAuthRateLimitPolicy,
            refreshTokenStore = RecordingRefreshTokenStore(),
        )

        assertThrows<InvalidCredentialsException> {
            trackedUseCase.execute(AuthenticateUserCommand(email = "Nobody@Example.com", rawPassword = "correct-password"))
        }

        assertTrue(rateLimiter.consumedKeys.contains("auth:login:email:nobody@example.com"))
    }
}
