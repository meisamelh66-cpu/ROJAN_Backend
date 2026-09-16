package ai.rojan.backend.application.auth

import ai.rojan.backend.application.port.RateLimiterPort
import ai.rojan.backend.application.port.RefreshTokenStorePort
import ai.rojan.backend.application.port.TokenProviderPort
import ai.rojan.backend.application.port.TokenSubject
import ai.rojan.backend.application.port.TokenType
import ai.rojan.backend.domain.common.InactiveUserException
import ai.rojan.backend.domain.common.InvalidTokenException
import ai.rojan.backend.domain.common.RefreshRateLimitExceededException
import ai.rojan.backend.domain.common.UserNotFoundException
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** [callerIp] is optional, same reasoning as [AuthenticateUserCommand.callerIp]. */
data class RefreshTokenCommand(val refreshToken: String, val callerIp: String? = null)

/**
 * Refresh Token Rotation (`BACKEND_REFRESH_TOKEN_SECURITY_PLAN.md`): every successful refresh
 * rotates the token (the presented one is superseded, never valid again) and detects reuse of an
 * already-rotated-out token by revoking its whole family, forcing a fresh login. This is the one
 * auth code path that talks to [RefreshTokenStorePort] (Redis in production) - deliberately not
 * [ai.rojan.backend.infrastructure.security.JwtAuthenticationFilter], which validates every
 * ordinary API request's access token and must stay Redis-free for horizontal scalability.
 */
class RefreshTokenUseCase(
    private val userRepository: UserRepository,
    private val tokenProvider: TokenProviderPort,
    private val rateLimiter: RateLimiterPort,
    private val policy: AuthRateLimitPolicy,
    private val refreshTokenStore: RefreshTokenStorePort,
) {
    fun execute(command: RefreshTokenCommand): AuthenticationResult {
        enforceRateLimit(command.callerIp)

        val subject = tokenProvider.validateAndExtractSubject(command.refreshToken)
        if (subject.type != TokenType.REFRESH) {
            // An access token is signed the same way — reject it here rather than
            // letting it double as a refresh credential.
            throw InvalidTokenException()
        }
        val userId = runCatching { UUID.fromString(subject.userId) }
            .getOrElse { throw InvalidTokenException() }

        val user: User = userRepository.findById(UserId(userId))
            ?: throw UserNotFoundException(subject.userId)
        if (!user.active) {
            throw InactiveUserException(user.id.value.toString())
        }

        val familyId = resolveFamilyId(subject)

        val accessToken = tokenProvider.generateAccessToken(user)
        val refreshToken = tokenProvider.generateRefreshToken(user, familyId)
        refreshTokenStore.activate(familyId, refreshToken.jti, Duration.between(Instant.now(), refreshToken.expiresAt))
        return AuthenticationResult(
            user = user,
            accessToken = accessToken.token,
            accessTokenExpiresAt = accessToken.expiresAt,
            refreshToken = refreshToken.token,
            refreshTokenExpiresAt = refreshToken.expiresAt,
        )
    }

    /**
     * Backward-compatible migration: a refresh token issued before this rotation scheme existed
     * carries no family claim at all. Rejecting it outright would unexpectedly log out every
     * already-signed-in user the moment this ships - instead it is migrated transparently on its
     * next use, treated as the sole founding member of a brand-new family that starts now.
     *
     * For a token that already carries a family claim, this is the reuse-detection check: if the
     * family's currently-active jti doesn't exist at all, the family was already revoked (an
     * earlier reuse event, or an explicit logout) or has expired - either way, refuse. If it
     * exists but doesn't match the jti just presented, this exact token was already rotated out
     * by an earlier, legitimate refresh and is now being replayed - revoke the whole family (both
     * the legitimate holder and whoever replayed this token are forced back to a fresh login,
     * which is the standard mitigation for this attack) and refuse this attempt too.
     *
     * Known, accepted trade-off (not a bug): two genuinely simultaneous refresh calls presenting
     * the SAME still-valid token (e.g. two browser tabs racing) both pass this check - each
     * rotates independently, and whichever `activate` call lands last in Redis "wins" the family;
     * the other caller's newly-issued refresh token is immediately superseded and will look like
     * reuse (and revoke the family) on its own next use. Strict rotation-based reuse detection
     * cannot distinguish this from a real replay without a grace window that would equally weaken
     * detection of a real attack - the same trade-off documented by mainstream implementations of
     * this pattern (e.g. Auth0's own refresh token rotation docs).
     */
    private fun resolveFamilyId(subject: TokenSubject): String {
        val presentedFamilyId = subject.familyId ?: return UUID.randomUUID().toString()

        val currentJti = refreshTokenStore.currentJti(presentedFamilyId) ?: throw InvalidTokenException()
        if (currentJti != subject.jti) {
            refreshTokenStore.revokeFamily(presentedFamilyId)
            throw InvalidTokenException()
        }
        return presentedFamilyId
    }

    /** Keyed by caller IP only - unlike login, there is no stable pre-validation identity to key by (the token itself might be garbage), and validating it first would mean a flood of malformed tokens never gets rate-limited at all. */
    private fun enforceRateLimit(callerIp: String?) {
        if (callerIp == null) return

        val ipKey = "auth:refresh:ip:$callerIp"
        if (!rateLimiter.tryConsume(ipKey, policy.refreshLimitPerIpWindow, Duration.ofSeconds(policy.refreshWindowSeconds))) {
            throw RefreshRateLimitExceededException(callerIp)
        }
    }
}
