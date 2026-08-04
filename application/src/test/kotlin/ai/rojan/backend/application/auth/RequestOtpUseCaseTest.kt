package ai.rojan.backend.application.auth

import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.OtpRateLimitExceededException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

private fun testPolicy() = OtpPolicy(
    ttlSeconds = 120,
    maxAttempts = 5,
    resendCooldownSeconds = 60,
    requestLimitPerPhoneShortWindow = 3,
    requestShortWindowSeconds = 600,
    requestLimitPerPhoneLongWindow = 5,
    requestLongWindowSeconds = 3600,
    requestLimitPerIpLongWindow = 10,
    verifyLimitPerPhoneWindow = 10,
    verifyWindowSeconds = 600,
)

class RequestOtpUseCaseTest {

    private val otpRepository = InMemoryOtpRepository()
    private val smsProvider = RecordingSmsProvider()

    private fun useCase(rateLimiter: RecordingRateLimiter = RecordingRateLimiter()) =
        RequestOtpUseCase(otpRepository, smsProvider, rateLimiter, testPolicy())

    @Test
    fun `issues and stores a hashed 6-digit code, and sends it via the SMS provider`() {
        val result = useCase().execute(RequestOtpCommand(phoneNumber = "+989123456789", callerIp = "1.2.3.4"))

        assertEquals("+989123456789", result.phoneNumber)
        assertEquals(120L, result.expiresInSeconds)
        assertEquals(60L, result.canResendAfterSeconds)

        val stored = otpRepository.findByPhoneNumber(PhoneNumber("+989123456789"))
        assertTrue(stored != null)
        val sentCode = smsProvider.lastCodeSentTo(PhoneNumber("+989123456789"))
        assertEquals(stored!!.codeHash, OtpHashing.hash(sentCode))
    }

    @Test
    fun `rejects a non-E164 phone number`() {
        assertThrows<IllegalArgumentException> {
            useCase().execute(RequestOtpCommand(phoneNumber = "09123456789", callerIp = "1.2.3.4"))
        }
    }

    @Test
    fun `enforces the per-phone request rate limit`() {
        val rateLimiter = RecordingRateLimiter(deniedKeyPrefixes = setOf("otp:request:phone:"))

        assertThrows<OtpRateLimitExceededException> {
            useCase(rateLimiter).execute(RequestOtpCommand(phoneNumber = "+989123456789", callerIp = "1.2.3.4"))
        }
        assertTrue(smsProvider.sent.isEmpty())
    }

    @Test
    fun `enforces the per-IP request rate limit`() {
        val rateLimiter = RecordingRateLimiter(deniedKeyPrefixes = setOf("otp:request:ip:"))

        assertThrows<OtpRateLimitExceededException> {
            useCase(rateLimiter).execute(RequestOtpCommand(phoneNumber = "+989123456789", callerIp = "1.2.3.4"))
        }
        assertTrue(smsProvider.sent.isEmpty())
    }

    @Test
    fun `skips the per-IP check when no caller IP is available`() {
        val rateLimiter = RecordingRateLimiter(deniedKeyPrefixes = setOf("otp:request:ip:"))

        useCase(rateLimiter).execute(RequestOtpCommand(phoneNumber = "+989123456789", callerIp = null))

        assertEquals(1, smsProvider.sent.size)
        assertTrue(rateLimiter.consumedKeys.none { it.startsWith("otp:request:ip:") })
    }
}
