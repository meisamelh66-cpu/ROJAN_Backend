package ai.rojan.backend.application.auth

import ai.rojan.backend.application.platformauthority.PlatformAuthorizationResolver
import ai.rojan.backend.application.port.RateLimiterPort
import ai.rojan.backend.application.port.RefreshTokenStorePort
import ai.rojan.backend.application.port.TokenProviderPort
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.auth.ReviewerAccessGrant
import ai.rojan.backend.domain.auth.ReviewerAccessRepository
import ai.rojan.backend.domain.common.OtpVerifyRateLimitExceededException
import ai.rojan.backend.domain.common.UserNotFoundException
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class ActivateReviewerAccessCommand(
    val callerId: UserId,
    val phoneNumber: String,
    val code: String,
    val validityDays: Long = 7,
)

/**
 * Cafe Bazaar reviewer access — activation. PLATFORM_ADMIN-only. Requires the
 * target phone to already have a real [ai.rojan.backend.domain.user.User] —
 * unlike the real OTP flow, this never auto-registers an account. Stores only
 * [OtpHashing.hash] of the code, matching how real OTP codes are hashed —
 * the plaintext code is never persisted.
 */
class ActivateReviewerAccessUseCase(
    private val reviewerAccessRepository: ReviewerAccessRepository,
    private val userRepository: UserRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(command: ActivateReviewerAccessCommand): ReviewerAccessGrant {
        platformAuthorization.requirePlatformAdmin(command.callerId)
        val phone = PhoneNumber(command.phoneNumber)
        userRepository.findByPhoneNumber(phone) ?: throw UserNotFoundException(phone.value)
        val now = Instant.now()
        val grant = ReviewerAccessGrant(
            phoneNumber = phone,
            codeHash = OtpHashing.hash(command.code),
            activatedAt = now,
            expiresAt = now.plus(Duration.ofDays(command.validityDays)),
            active = true,
        )
        return reviewerAccessRepository.save(grant)
    }
}

data class DeactivateReviewerAccessCommand(val callerId: UserId, val phoneNumber: String)

/** The "revoke early" lever — PLATFORM_ADMIN-only, idempotent (mirrors [LogoutUseCase]: nothing to revoke is still a successful no-op, never an error). Takes effect immediately — the very next [TryReviewerAccessLoginUseCase] check reads this row fresh. */
class DeactivateReviewerAccessUseCase(
    private val reviewerAccessRepository: ReviewerAccessRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(command: DeactivateReviewerAccessCommand) {
        platformAuthorization.requirePlatformAdmin(command.callerId)
        val phone = PhoneNumber(command.phoneNumber)
        val grant = reviewerAccessRepository.findByPhoneNumber(phone) ?: return
        reviewerAccessRepository.save(grant.copy(active = false))
    }
}

/**
 * The reviewer-login check consulted by [ai.rojan.backend.api.auth.AuthController.verify]
 * BEFORE [VerifyOtpUseCase] — a fully separate, additive path; [VerifyOtpUseCase]
 * itself is never modified or even referenced here. Returns `null` for every
 * request this mechanism doesn't apply to (no grant row for that phone,
 * wrong code, expired, deactivated, or the account no longer exists), so the
 * caller falls through to the real, completely unmodified OTP flow.
 *
 * Rate limiting: deliberately consumes the exact same [RateLimiterPort] bean,
 * the exact same key format (`"otp:verify:phone:${phone.value}"`), and the
 * exact same [OtpPolicy] values [VerifyOtpUseCase.enforceVerifyRateLimit]
 * uses — not a second limiter, not a separate policy. Only reached when a
 * grant row exists for the requested phone at all (see (A) below), so this
 * has zero overhead and zero behavioral effect for every other phone number.
 * Duplicated as a few lines rather than extracted out of `VerifyOtpUseCase`,
 * since extracting would mean editing that file — which this design
 * deliberately never does.
 */
class TryReviewerAccessLoginUseCase(
    private val reviewerAccessRepository: ReviewerAccessRepository,
    private val userRepository: UserRepository,
    private val tokenProvider: TokenProviderPort,
    private val refreshTokenStore: RefreshTokenStorePort,
    private val rateLimiter: RateLimiterPort,
    private val policy: OtpPolicy,
) {
    fun execute(phoneNumber: String, code: String): AuthenticationResult? {
        val phone = PhoneNumber(phoneNumber)
        val grant = reviewerAccessRepository.findByPhoneNumber(phone) ?: return null // (A) — zero Redis/rate-limit overhead for every other phone

        enforceSharedVerifyRateLimit(phone)

        if (!grant.isUsableAt(Instant.now())) return null
        if (grant.codeHash != OtpHashing.hash(code)) return null

        val user = userRepository.findByPhoneNumber(phone) ?: return null
        return issueTokensFor(user)
    }

    private fun issueTokensFor(user: ai.rojan.backend.domain.user.User): AuthenticationResult {
        val accessToken = tokenProvider.generateAccessToken(user)
        val familyId = UUID.randomUUID().toString()
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

    /** Identical key/limit/window to [VerifyOtpUseCase.enforceVerifyRateLimit] — same bean, same bucket, intentionally duplicated rather than extracted (see class doc). */
    private fun enforceSharedVerifyRateLimit(phone: PhoneNumber) {
        val key = "otp:verify:phone:${phone.value}"
        if (!rateLimiter.tryConsume(key, policy.verifyLimitPerPhoneWindow, Duration.ofSeconds(policy.verifyWindowSeconds))) {
            throw OtpVerifyRateLimitExceededException(phone.value)
        }
    }
}
