package ai.rojan.backend.application.auth

import ai.rojan.backend.application.port.RefreshTokenStorePort
import ai.rojan.backend.application.port.TokenProviderPort
import ai.rojan.backend.application.port.TokenType

data class LogoutCommand(val refreshToken: String)

/**
 * Refresh Token Rotation (`BACKEND_REFRESH_TOKEN_SECURITY_PLAN.md`): real, server-side session
 * revocation - the capability [RefreshTokenUseCase]'s reuse detection alone doesn't provide
 * (reuse detection only fires *after* a stolen token is used; a deliberate logout must work
 * immediately, on demand, for a token nobody has stolen). Revokes the presented token's entire
 * refresh-token family, so no further refresh against it succeeds from any device sharing that
 * family - "logout" in the single-family sense; a caller wanting "log out everywhere" would need
 * to revoke every family tied to the user, which needs its own indexing decision and is out of
 * this task's scope (see the security plan's own "if a desired product feature" note).
 *
 * Deliberately never throws: a token that's already invalid, expired, malformed, or an access
 * token has nothing left to revoke, and from the caller's point of view "logging out" with a
 * token like that should still just succeed - there's nothing for a client to retry or recover
 * from either way.
 */
class LogoutUseCase(
    private val tokenProvider: TokenProviderPort,
    private val refreshTokenStore: RefreshTokenStorePort,
) {
    fun execute(command: LogoutCommand) {
        val subject = runCatching { tokenProvider.validateAndExtractSubject(command.refreshToken) }.getOrNull()
            ?: return
        if (subject.type != TokenType.REFRESH) {
            return
        }
        // A legacy (pre-rotation) refresh token has no family to revoke - logging out means the
        // client discarding it, exactly as it did before this feature existed.
        subject.familyId?.let { refreshTokenStore.revokeFamily(it) }
    }
}
