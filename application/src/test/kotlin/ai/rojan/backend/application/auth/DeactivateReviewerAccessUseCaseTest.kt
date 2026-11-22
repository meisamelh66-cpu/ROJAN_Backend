package ai.rojan.backend.application.auth

import ai.rojan.backend.application.platformauthority.PlatformAuthorizationResolver
import ai.rojan.backend.application.salon.InMemorySalonUserRepository
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.auth.ReviewerAccessGrant
import ai.rojan.backend.domain.common.PlatformAccessDeniedException
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserRole
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/** The "revoke reviewer access early" lever — PLATFORM_ADMIN-only, idempotent (mirrors LogoutUseCase: nothing to revoke is still a successful no-op, never an error). */
class DeactivateReviewerAccessUseCaseTest {

    private val userRepository = InMemorySalonUserRepository()
    private val reviewerAccessRepository = InMemoryReviewerAccessRepository()
    private val platformAuthorization = PlatformAuthorizationResolver(userRepository)
    private val useCase = DeactivateReviewerAccessUseCase(reviewerAccessRepository, platformAuthorization)

    private val admin = User.registerWithPhone(PhoneNumber("+989000000001"), "Admin", UserRole.PLATFORM_ADMIN).also { userRepository.save(it) }
    private val customer = User.registerWithPhone(PhoneNumber("+989000000002"), "Customer", UserRole.CUSTOMER).also { userRepository.save(it) }
    private val reviewerPhone = PhoneNumber("+989164987585")

    @Test
    fun `a non-admin caller is rejected`() {
        reviewerAccessRepository.save(activeGrant())
        assertThrows<PlatformAccessDeniedException> {
            useCase.execute(DeactivateReviewerAccessCommand(callerId = customer.id, phoneNumber = reviewerPhone.value))
        }
        assertTrue(reviewerAccessRepository.findByPhoneNumber(reviewerPhone)!!.active)
    }

    @Test
    fun `deactivating a phone with no grant at all is a safe no-op, not an error`() {
        useCase.execute(DeactivateReviewerAccessCommand(callerId = admin.id, phoneNumber = reviewerPhone.value))
        assertFalse(reviewerAccessRepository.findByPhoneNumber(reviewerPhone)?.active ?: false)
    }

    @Test
    fun `an admin deactivates an active grant`() {
        reviewerAccessRepository.save(activeGrant())

        useCase.execute(DeactivateReviewerAccessCommand(callerId = admin.id, phoneNumber = reviewerPhone.value))

        assertFalse(reviewerAccessRepository.findByPhoneNumber(reviewerPhone)!!.active)
    }

    private fun activeGrant() = ReviewerAccessGrant(
        phoneNumber = reviewerPhone,
        codeHash = OtpHashing.hash("1324"),
        activatedAt = Instant.now(),
        expiresAt = Instant.now().plusSeconds(604_800),
        active = true,
    )
}
