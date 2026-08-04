package ai.rojan.backend.application.auth

import ai.rojan.backend.application.port.IssuedToken
import ai.rojan.backend.application.port.PasswordEncoderPort
import ai.rojan.backend.application.port.TokenProviderPort
import ai.rojan.backend.application.port.TokenSubject
import ai.rojan.backend.application.port.TokenType
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.InvalidCredentialsException
import ai.rojan.backend.domain.user.Email
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository
import ai.rojan.backend.domain.user.UserRole
import org.junit.jupiter.api.Assertions.assertEquals
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
}

private class MatchingPasswordEncoder(private val correctRawPassword: String) : PasswordEncoderPort {
    override fun encode(rawPassword: String): String = rawPassword
    override fun matches(rawPassword: String, encodedPassword: String): Boolean =
        rawPassword == correctRawPassword
}

private class FakeTokenProvider : TokenProviderPort {
    override fun generateAccessToken(user: User) =
        IssuedToken("access-${user.id.value}", Instant.now().plusSeconds(900))

    override fun generateRefreshToken(user: User) =
        IssuedToken("refresh-${user.id.value}", Instant.now().plusSeconds(2_592_000))

    override fun validateAndExtractSubject(token: String) =
        TokenSubject(userId = token.substringAfter("-"), email = "", role = "", type = TokenType.ACCESS)
}

class AuthenticateUserUseCaseTest {

    private val user = User.register(
        email = Email("member@example.com"),
        passwordHash = "irrelevant-because-fake-encoder-ignores-it",
        fullName = "Member",
        role = UserRole.CUSTOMER,
    )

    private val useCase = AuthenticateUserUseCase(
        userRepository = SingleUserRepository(user),
        passwordEncoder = MatchingPasswordEncoder(correctRawPassword = "correct-password"),
        tokenProvider = FakeTokenProvider(),
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
}
