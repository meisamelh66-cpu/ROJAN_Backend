package ai.rojan.backend.application.auth

import ai.rojan.backend.application.port.IssuedToken
import ai.rojan.backend.application.port.TokenProviderPort
import ai.rojan.backend.application.port.TokenSubject
import ai.rojan.backend.application.port.TokenType
import ai.rojan.backend.domain.common.InactiveUserException
import ai.rojan.backend.domain.common.InvalidTokenException
import ai.rojan.backend.domain.common.UserNotFoundException
import ai.rojan.backend.domain.user.Email
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository
import ai.rojan.backend.domain.user.UserRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

private class SoleUserRepository(private val user: User) : UserRepository {
    override fun save(user: User): User = user
    override fun findById(id: UserId): User? = user.takeIf { it.id == id }
    override fun findByEmail(email: Email): User? = user.takeIf { it.email == email }
    override fun existsByEmail(email: Email): Boolean = user.email == email
}

/**
 * Encodes the token's claimed type/subject/nonce directly in its string,
 * e.g. "REFRESH:<userId>:<nonce>" — the nonce keeps successive tokens for
 * the same user distinct, same as real JWTs (which vary by issuedAt), so
 * "refresh issues a genuinely new token" is actually exercised.
 */
private class EncodedTokenProvider : TokenProviderPort {
    private var nonce = 0

    override fun generateAccessToken(user: User) =
        IssuedToken("${TokenType.ACCESS}:${user.id.value}:${nonce++}", Instant.now().plusSeconds(900))

    override fun generateRefreshToken(user: User) =
        IssuedToken("${TokenType.REFRESH}:${user.id.value}:${nonce++}", Instant.now().plusSeconds(2_592_000))

    override fun validateAndExtractSubject(token: String): TokenSubject {
        val (type, userId) = token.split(":", limit = 3)
        return TokenSubject(userId = userId, email = "", role = "", type = TokenType.valueOf(type))
    }
}

class RefreshTokenUseCaseTest {

    private val user = User.register(
        email = Email("member@example.com"),
        passwordHash = "irrelevant",
        fullName = "Member",
        role = UserRole.CUSTOMER,
    )

    private val tokenProvider = EncodedTokenProvider()
    private val useCase = RefreshTokenUseCase(
        userRepository = SoleUserRepository(user),
        tokenProvider = tokenProvider,
    )

    @Test
    fun `issues a new token pair for a valid refresh token`() {
        val refreshToken = tokenProvider.generateRefreshToken(user).token

        val result = useCase.execute(RefreshTokenCommand(refreshToken))

        assertEquals(user.id.value, result.user.id.value)
        assertTrue(result.accessToken.startsWith("${TokenType.ACCESS}:${user.id.value}:"))
        assertNotEquals(refreshToken, result.refreshToken)
    }

    @Test
    fun `rejects an access token presented as a refresh token`() {
        val accessToken = tokenProvider.generateAccessToken(user).token

        assertThrows<InvalidTokenException> {
            useCase.execute(RefreshTokenCommand(accessToken))
        }
    }

    @Test
    fun `rejects a refresh token for a user that no longer exists`() {
        val strangerId = UserId.new()
        val strangerToken = "${TokenType.REFRESH}:${strangerId.value}"

        assertThrows<UserNotFoundException> {
            useCase.execute(RefreshTokenCommand(strangerToken))
        }
    }

    @Test
    fun `rejects a refresh token for a deactivated user`() {
        user.deactivate()
        val refreshToken = tokenProvider.generateRefreshToken(user).token

        assertThrows<InactiveUserException> {
            useCase.execute(RefreshTokenCommand(refreshToken))
        }
    }
}
