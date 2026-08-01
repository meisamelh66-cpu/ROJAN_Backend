package ai.rojan.backend.application.auth

import ai.rojan.backend.application.port.TokenProviderPort
import ai.rojan.backend.domain.common.InactiveUserException
import ai.rojan.backend.domain.common.InvalidTokenException
import ai.rojan.backend.domain.common.UserNotFoundException
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository
import java.util.UUID

data class RefreshTokenCommand(val refreshToken: String)

class RefreshTokenUseCase(
    private val userRepository: UserRepository,
    private val tokenProvider: TokenProviderPort,
) {
    fun execute(command: RefreshTokenCommand): AuthenticationResult {
        val subject = tokenProvider.validateAndExtractSubject(command.refreshToken)
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
}
