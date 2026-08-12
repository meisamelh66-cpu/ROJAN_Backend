package ai.rojan.backend.application.auth

import ai.rojan.backend.application.port.RateLimiterPort
import ai.rojan.backend.application.port.SmsProviderPort
import ai.rojan.backend.domain.auth.OneTimePassword
import ai.rojan.backend.domain.auth.OtpRepository
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.OtpRateLimitExceededException
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

data class RequestOtpCommand(val phoneNumber: String, val callerIp: String?)

data class OtpIssuedResult(val phoneNumber: String, val expiresInSeconds: Long, val canResendAfterSeconds: Long)

/**
 * Serves BOTH `POST /auth/otp/request` and `POST /auth/otp/resend` (see
 * `AuthController`) — one use case, two entry points, per the approved
 * Mobile Auth Architecture: functionally the same operation (issue a fresh
 * code, replacing any live one), differing only in which URL a client
 * called to express its own intent (initial send vs. explicit resend). Both
 * draw from the same rate-limit budget per phone — resend is not a way to
 * bypass the request limit.
 */
class RequestOtpUseCase(
    private val otpRepository: OtpRepository,
    private val smsProvider: SmsProviderPort,
    private val rateLimiter: RateLimiterPort,
    private val policy: OtpPolicy,
) {
    fun execute(command: RequestOtpCommand): OtpIssuedResult {
        val phone = PhoneNumber(command.phoneNumber)
        enforceRateLimits(phone, command.callerIp)

        val code = generateCode()
        val now = Instant.now()
        val otp = OneTimePassword.issue(phone, OtpHashing.hash(code), now, policy.ttlSeconds, policy.maxAttempts)
        otpRepository.save(otp)
        smsProvider.send(phone, "Your ROJAN verification code is $code. It expires in ${policy.ttlSeconds / 60} minutes.")

        return OtpIssuedResult(phone.value, policy.ttlSeconds, policy.resendCooldownSeconds)
    }

    /** Deliberately consumes every applicable budget regardless of which one is already exhausted — a rejected attempt still happened and still counts against every window it touches. */
    private fun enforceRateLimits(phone: PhoneNumber, callerIp: String?) {
        val phoneKey = "otp:request:phone:${phone.value}"
        val withinShortWindow = rateLimiter.tryConsume(phoneKey, policy.requestLimitPerPhoneShortWindow, Duration.ofSeconds(policy.requestShortWindowSeconds))
        val withinLongWindow = rateLimiter.tryConsume(phoneKey, policy.requestLimitPerPhoneLongWindow, Duration.ofSeconds(policy.requestLongWindowSeconds))
        if (!withinShortWindow || !withinLongWindow) {
            throw OtpRateLimitExceededException(phone.value)
        }

        if (callerIp != null) {
            val ipKey = "otp:request:ip:$callerIp"
            val withinIpWindow = rateLimiter.tryConsume(ipKey, policy.requestLimitPerIpLongWindow, Duration.ofSeconds(policy.requestLongWindowSeconds))
            if (!withinIpWindow) {
                throw OtpRateLimitExceededException(phone.value)
            }
        }
    }

    private fun generateCode(): String {
        val bound = Math.pow(10.0, policy.codeLength.toDouble()).toInt()
        return SECURE_RANDOM.nextInt(bound).toString().padStart(policy.codeLength, '0')
    }

    private companion object {
        val SECURE_RANDOM = SecureRandom()
    }
}
