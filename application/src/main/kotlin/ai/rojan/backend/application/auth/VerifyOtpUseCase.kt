package ai.rojan.backend.application.auth

import ai.rojan.backend.application.port.RateLimiterPort
import ai.rojan.backend.application.port.TokenProviderPort
import ai.rojan.backend.domain.auth.OtpRepository
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.InvalidOtpException
import ai.rojan.backend.domain.common.OtpVerifyRateLimitExceededException
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserRepository
import ai.rojan.backend.domain.user.UserRole
import java.time.Duration
import java.time.Instant

/** [fullName] is optional — the approved Owner App flow (Mobile Number → OTP → JWT) collects no name during sign-in; a placeholder is used for a brand-new account and can be changed later once a profile-editing feature exists. Not supplying one is not an error. */
data class VerifyOtpCommand(val phoneNumber: String, val code: String, val fullName: String? = null)

/**
 * Verifies an OTP and returns the SAME [AuthenticationResult] type
 * [AuthenticateUserUseCase]/[RefreshTokenUseCase] already return — this is
 * what lets `AuthController` reuse its existing `AuthenticationResult.toResponse()`
 * mapping completely unchanged (Phase 1 order: "must return existing
 * AuthResponse format"). First-time verification of a previously-unseen
 * phone number auto-registers the account — no separate register step
 * exists for phone-only accounts (see `User.registerWithPhone`).
 *
 * Default role for an auto-registered account is [UserRole.CUSTOMER] — a
 * judgment call, flagged here rather than made silently: role is not an
 * authorization gate anywhere in this codebase today (confirmed across
 * every prior audit), so this choice is not currently load-bearing, but if
 * the Owner App specifically needs auto-registered accounts to default to
 * [UserRole.MANAGER] instead, that needs an explicit `role` field on
 * `OtpVerifyRequest` — not assumed here.
 */
class VerifyOtpUseCase(
    private val otpRepository: OtpRepository,
    private val userRepository: UserRepository,
    private val tokenProvider: TokenProviderPort,
    private val rateLimiter: RateLimiterPort,
    private val policy: OtpPolicy,
) {
    fun execute(command: VerifyOtpCommand): AuthenticationResult {
        val phone = PhoneNumber(command.phoneNumber)
        enforceVerifyRateLimit(phone)

        val otp = otpRepository.findByPhoneNumber(phone) ?: throw InvalidOtpException(phone.value)
        val now = Instant.now()
        if (otp.isExpired(now) || otp.isExhausted) {
            otpRepository.delete(phone)
            throw InvalidOtpException(phone.value)
        }

        if (otp.codeHash != OtpHashing.hash(command.code)) {
            val updated = otp.withFailedAttempt()
            if (updated.isExhausted) {
                otpRepository.delete(phone)
            } else {
                otpRepository.save(updated)
            }
            throw InvalidOtpException(phone.value)
        }

        otpRepository.delete(phone) // one-time use, win or lose, new user or returning

        val user = userRepository.findByPhoneNumber(phone)
            ?: userRepository.save(
                User.registerWithPhone(
                    phoneNumber = phone,
                    fullName = command.fullName?.takeIf { it.isNotBlank() } ?: DEFAULT_FULL_NAME,
                    role = UserRole.CUSTOMER,
                ),
            )

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

    /** Distinct from [ai.rojan.backend.domain.auth.OneTimePassword.attemptsRemaining] (which guards one issued code) — this guards the `/otp/verify` endpoint itself against rapid guessing spread across many freshly-requested codes. */
    private fun enforceVerifyRateLimit(phone: PhoneNumber) {
        val key = "otp:verify:phone:${phone.value}"
        if (!rateLimiter.tryConsume(key, policy.verifyLimitPerPhoneWindow, Duration.ofSeconds(policy.verifyWindowSeconds))) {
            throw OtpVerifyRateLimitExceededException(phone.value)
        }
    }

    private companion object {
        const val DEFAULT_FULL_NAME = "ROJAN User"
    }
}
