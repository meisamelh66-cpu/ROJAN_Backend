package ai.rojan.backend.application.auth

import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.auth.ReviewerAccessGrant
import ai.rojan.backend.domain.common.OtpVerifyRateLimitExceededException
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/**
 * Cafe Bazaar reviewer access, core check. Deliberately exercises the exact
 * same [RecordingRateLimiter]/[localTestOtpPolicy] fixtures
 * [VerifyOtpUseCaseTest] uses — not a parallel set — so a passing
 * "uses the shared key" test here is directly comparable to that file's own
 * rate-limit behavior, proving no second/weaker limit was invented.
 */
class TryReviewerAccessLoginUseCaseTest {

    /** [VerifyOtpUseCaseTest]'s own `defaultTestOtpPolicy` is file-private to that file (Kotlin top-level `private` is file-scoped, not package-scoped) — this is a local, same-shaped copy, not a shared fixture, so this file has no dependency on that one. */
    private val localTestOtpPolicy = OtpPolicy(
        codeLength = 4,
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

    private val reviewerPhone = PhoneNumber("+989164987585")
    private val otherPhone = PhoneNumber("+989123456789")
    private val reviewerAccessRepository = InMemoryReviewerAccessRepository()
    private val userRepository = InMemoryOtpUserRepository()
    private val tokenProvider = FakeReviewerTokenProvider()
    private val refreshTokenStore = RecordingRefreshTokenStore()

    private fun useCase(rateLimiter: RecordingRateLimiter = RecordingRateLimiter(), policy: OtpPolicy = localTestOtpPolicy) =
        TryReviewerAccessLoginUseCase(reviewerAccessRepository, userRepository, tokenProvider, refreshTokenStore, rateLimiter, policy)

    private val reviewerUser = User.registerWithPhone(reviewerPhone, "گیتا", UserRole.CUSTOMER).also { userRepository.register(it) }

    private fun activeGrant(phone: PhoneNumber = reviewerPhone, code: String = "1324", now: Instant = Instant.now()) =
        ReviewerAccessGrant(phoneNumber = phone, codeHash = OtpHashing.hash(code), activatedAt = now, expiresAt = now.plusSeconds(604_800), active = true)

    @Test
    fun `successful reviewer login with the correct code returns the real account`() {
        reviewerAccessRepository.save(activeGrant())

        val result = useCase().execute(reviewerPhone.value, "1324")

        assertEquals(reviewerUser.id, result?.user?.id)
    }

    @Test
    fun `wrong code does not authenticate`() {
        reviewerAccessRepository.save(activeGrant())
        assertNull(useCase().execute(reviewerPhone.value, "0000"))
    }

    @Test
    fun `a phone with no grant at all never matches`() {
        assertNull(useCase().execute(otherPhone.value, "1324"))
    }

    @Test
    fun `reviewer code cannot authenticate another phone - a grant for one phone never matches a different phone's request`() {
        reviewerAccessRepository.save(activeGrant(phone = reviewerPhone, code = "1324"))
        userRepository.register(User.registerWithPhone(otherPhone, "Someone Else", UserRole.CUSTOMER))

        assertNull(useCase().execute(otherPhone.value, "1324"))
    }

    @Test
    fun `an expired grant does not authenticate even with the correct code`() {
        val past = Instant.now().minusSeconds(10)
        reviewerAccessRepository.save(activeGrant(now = past.minusSeconds(604_800)).copy(expiresAt = past))

        assertNull(useCase().execute(reviewerPhone.value, "1324"))
    }

    @Test
    fun `a deactivated grant does not authenticate even with the correct code`() {
        reviewerAccessRepository.save(activeGrant().copy(active = false))

        assertNull(useCase().execute(reviewerPhone.value, "1324"))
    }

    @Test
    fun `a grant whose account no longer exists fails safe rather than authenticating`() {
        val ghostPhone = PhoneNumber("+989199999999")
        reviewerAccessRepository.save(activeGrant(phone = ghostPhone)) // no matching userRepository.register call

        assertNull(useCase().execute(ghostPhone.value, "1324"))
    }

    @Test
    fun `successful login issues the normal access and refresh tokens, same as a real OTP login`() {
        reviewerAccessRepository.save(activeGrant())

        val result = useCase().execute(reviewerPhone.value, "1324")

        assertEquals("access-${reviewerUser.id.value}", result?.accessToken)
        assertEquals("refresh-${reviewerUser.id.value}", result?.refreshToken)
        assertEquals(1, refreshTokenStore.activations.size)
    }

    @Test
    fun `the reviewer path consumes the exact same rate-limit key VerifyOtpUseCase uses for this phone`() {
        reviewerAccessRepository.save(activeGrant())
        val rateLimiter = RecordingRateLimiter()

        useCase(rateLimiter).execute(reviewerPhone.value, "1324")

        assertEquals(listOf("otp:verify:phone:${reviewerPhone.value}"), rateLimiter.consumedKeys)
    }

    @Test
    fun `a phone with no grant never touches the rate limiter at all - zero overhead for every other user`() {
        val rateLimiter = RecordingRateLimiter()

        useCase(rateLimiter).execute(otherPhone.value, "1324")

        assertTrue(rateLimiter.consumedKeys.isEmpty())
    }

    @Test
    fun `an exhausted shared bucket rejects even the correct reviewer code`() {
        reviewerAccessRepository.save(activeGrant())
        val exhaustedLimiter = RecordingRateLimiter(deniedKeyPrefixes = setOf("otp:verify:phone:"))

        assertThrows<OtpVerifyRateLimitExceededException> {
            useCase(exhaustedLimiter).execute(reviewerPhone.value, "1324")
        }
    }

    @Test
    fun `a correct reviewer login consumes the shared bucket exactly once, never double-charged`() {
        reviewerAccessRepository.save(activeGrant())
        val rateLimiter = RecordingRateLimiter()

        useCase(rateLimiter).execute(reviewerPhone.value, "1324")

        assertEquals(1, rateLimiter.consumedKeys.size)
    }

    @Test
    fun `a failed reviewer-code attempt still consumes the shared bucket exactly once, so a caller falling through to VerifyOtpUseCase next adds a second, cumulative consumption rather than skipping rate-limiting entirely`() {
        reviewerAccessRepository.save(activeGrant())
        val rateLimiter = RecordingRateLimiter()

        val result = useCase(rateLimiter).execute(reviewerPhone.value, "0000")

        assertNull(result)
        assertEquals(1, rateLimiter.consumedKeys.size)
        assertEquals("otp:verify:phone:${reviewerPhone.value}", rateLimiter.consumedKeys.single())
    }
}
