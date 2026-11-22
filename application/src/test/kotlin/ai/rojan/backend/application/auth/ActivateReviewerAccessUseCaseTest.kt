package ai.rojan.backend.application.auth

import ai.rojan.backend.application.platformauthority.PlatformAuthorizationResolver
import ai.rojan.backend.application.salon.InMemorySalonUserRepository
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.PlatformAccessDeniedException
import ai.rojan.backend.domain.common.UserNotFoundException
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant

/**
 * Cafe Bazaar reviewer access: PLATFORM_ADMIN-only, and only ever against a
 * phone number that already has a real account (reviewer access logs into
 * an existing account - unlike real OTP, it never auto-registers one).
 */
class ActivateReviewerAccessUseCaseTest {

    private val userRepository = InMemorySalonUserRepository()
    private val reviewerAccessRepository = InMemoryReviewerAccessRepository()
    private val platformAuthorization = PlatformAuthorizationResolver(userRepository)
    private val useCase = ActivateReviewerAccessUseCase(reviewerAccessRepository, userRepository, platformAuthorization)

    private val admin = User.registerWithPhone(PhoneNumber("+989000000001"), "Admin", UserRole.PLATFORM_ADMIN).also { userRepository.save(it) }
    private val customer = User.registerWithPhone(PhoneNumber("+989000000002"), "Customer", UserRole.CUSTOMER).also { userRepository.save(it) }
    private val reviewerPhone = PhoneNumber("+989164987585")
    private val reviewerAccount = User.registerWithPhone(reviewerPhone, "گیتا", UserRole.CUSTOMER).also { userRepository.save(it) }

    @Test
    fun `a non-admin caller is rejected`() {
        assertThrows<PlatformAccessDeniedException> {
            useCase.execute(ActivateReviewerAccessCommand(callerId = customer.id, phoneNumber = reviewerPhone.value, code = "1324"))
        }
        assertEquals(null, reviewerAccessRepository.findByPhoneNumber(reviewerPhone))
    }

    @Test
    fun `activating against a phone with no existing account is rejected`() {
        assertThrows<UserNotFoundException> {
            useCase.execute(ActivateReviewerAccessCommand(callerId = admin.id, phoneNumber = "+989099999999", code = "1324"))
        }
    }

    @Test
    fun `an admin activates a grant for an existing account, defaulting to 7 days`() {
        val before = Instant.now()
        val grant = useCase.execute(ActivateReviewerAccessCommand(callerId = admin.id, phoneNumber = reviewerPhone.value, code = "1324"))
        val after = Instant.now()

        assertEquals(reviewerPhone, grant.phoneNumber)
        assertTrue(grant.active)
        assertTrue(!grant.activatedAt.isBefore(before) && !grant.activatedAt.isAfter(after))
        assertEquals(grant.activatedAt.plus(Duration.ofDays(7)), grant.expiresAt)
        assertEquals(OtpHashing.hash("1324"), grant.codeHash)
    }

    @Test
    fun `an explicit validityDays overrides the 7-day default`() {
        val grant = useCase.execute(
            ActivateReviewerAccessCommand(callerId = admin.id, phoneNumber = reviewerPhone.value, code = "1324", validityDays = 3),
        )
        assertEquals(grant.activatedAt.plus(Duration.ofDays(3)), grant.expiresAt)
    }

    @Test
    fun `activating again for the same phone replaces the previous grant rather than creating a second one`() {
        useCase.execute(ActivateReviewerAccessCommand(callerId = admin.id, phoneNumber = reviewerPhone.value, code = "1324"))
        useCase.execute(ActivateReviewerAccessCommand(callerId = admin.id, phoneNumber = reviewerPhone.value, code = "9999"))

        val grant = reviewerAccessRepository.findByPhoneNumber(reviewerPhone)
        assertEquals(OtpHashing.hash("9999"), grant!!.codeHash)
    }
}
