package ai.rojan.backend.application.auth

import ai.rojan.backend.application.port.IssuedToken
import ai.rojan.backend.application.port.TokenProviderPort
import ai.rojan.backend.application.port.TokenSubject
import ai.rojan.backend.application.port.TokenType
import ai.rojan.backend.domain.auth.OneTimePassword
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.InvalidOtpException
import ai.rojan.backend.domain.common.OtpVerifyRateLimitExceededException
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

private class FakeOtpTokenProvider : TokenProviderPort {
    override fun generateAccessToken(user: User) = IssuedToken("access-${user.id.value}", Instant.now().plusSeconds(900))
    override fun generateRefreshToken(user: User) = IssuedToken("refresh-${user.id.value}", Instant.now().plusSeconds(2_592_000))
    override fun validateAndExtractSubject(token: String) =
        TokenSubject(userId = token.substringAfter("-"), email = null, role = "", type = TokenType.ACCESS)
}

class VerifyOtpUseCaseTest {

    private val phone = PhoneNumber("+989123456789")
    private val otpRepository = InMemoryOtpRepository()
    private val userRepository = InMemoryOtpUserRepository()
    private val tokenProvider = FakeOtpTokenProvider()

    private fun useCase(policy: OtpPolicy = defaultTestOtpPolicy, rateLimiter: RecordingRateLimiter = RecordingRateLimiter()) =
        VerifyOtpUseCase(otpRepository, userRepository, tokenProvider, rateLimiter, policy)

    private fun issueOtp(code: String = "123456", now: Instant = Instant.now(), ttlSeconds: Long = 120, maxAttempts: Int = 5) {
        otpRepository.save(OneTimePassword.issue(phone, OtpHashing.hash(code), now, ttlSeconds, maxAttempts))
    }

    @Test
    fun `verifies a correct code and creates a new phone-only account on first login`() {
        issueOtp("123456")

        val result = useCase().execute(VerifyOtpCommand(phoneNumber = "+989123456789", code = "123456", fullName = "Jane Doe"))

        assertEquals("Jane Doe", result.user.fullName)
        assertEquals(phone, result.user.phoneNumber)
        assertNull(result.user.email)
        assertEquals(UserRole.CUSTOMER, result.user.role)
        assertEquals("access-${result.user.id.value}", result.accessToken)
        assertNull(otpRepository.findByPhoneNumber(phone))
    }

    @Test
    fun `reuses the existing account on a returning user's verification instead of creating a duplicate`() {
        val existing = User.registerWithPhone(phoneNumber = phone, fullName = "Existing User", role = UserRole.CUSTOMER)
        userRepository.register(existing)
        issueOtp("654321")

        val result = useCase().execute(VerifyOtpCommand(phoneNumber = "+989123456789", code = "654321"))

        assertEquals(existing.id, result.user.id)
        assertEquals("Existing User", result.user.fullName)
    }

    @Test
    fun `rejects a wrong code and decrements the remaining attempts rather than deleting the code outright`() {
        issueOtp("123456", maxAttempts = 5)

        assertThrows<InvalidOtpException> {
            useCase().execute(VerifyOtpCommand(phoneNumber = "+989123456789", code = "000000"))
        }

        val stillPending = otpRepository.findByPhoneNumber(phone)
        assertNotNull(stillPending)
        assertEquals(4, stillPending!!.attemptsRemaining)
    }

    @Test
    fun `deletes the code once attempts are exhausted`() {
        issueOtp("123456", maxAttempts = 1)

        assertThrows<InvalidOtpException> {
            useCase().execute(VerifyOtpCommand(phoneNumber = "+989123456789", code = "000000"))
        }

        assertNull(otpRepository.findByPhoneNumber(phone))
    }

    @Test
    fun `rejects an expired code`() {
        issueOtp("123456", now = Instant.now().minusSeconds(300), ttlSeconds = 120)

        assertThrows<InvalidOtpException> {
            useCase().execute(VerifyOtpCommand(phoneNumber = "+989123456789", code = "123456"))
        }
    }

    @Test
    fun `rejects verification when no code was ever requested for this phone`() {
        assertThrows<InvalidOtpException> {
            useCase().execute(VerifyOtpCommand(phoneNumber = "+989123456789", code = "123456"))
        }
    }

    @Test
    fun `enforces the per-phone verify rate limit`() {
        issueOtp("123456")
        val rateLimiter = RecordingRateLimiter(deniedKeyPrefixes = setOf("otp:verify:phone:"))

        assertThrows<OtpVerifyRateLimitExceededException> {
            useCase(rateLimiter = rateLimiter).execute(VerifyOtpCommand(phoneNumber = "+989123456789", code = "123456"))
        }
    }
}

private val defaultTestOtpPolicy = OtpPolicy(
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
