package ai.rojan.backend.application.verification

import ai.rojan.backend.application.document.InMemorySalonDocumentRepository
import ai.rojan.backend.application.platformauthority.PlatformAuthorizationResolver
import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.application.salon.InMemorySalonUserRepository
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.PlatformAccessDeniedException
import ai.rojan.backend.domain.common.SalonVerificationNotFoundException
import ai.rojan.backend.domain.common.VerificationAlreadyPendingException
import ai.rojan.backend.domain.document.DocumentType
import ai.rojan.backend.domain.document.SalonDocument
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonOnboardingStatus
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRole
import ai.rojan.backend.domain.verification.SalonVerificationId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

private fun newSalon(ownerId: UserId = UserId.new()) = Salon.create(
    ownerId = ownerId,
    name = "Glow Salon",
    description = null,
    phone = "+1 555 0100",
    email = null,
    address = "1 Main St",
)

/**
 * Every test below initiates with [admin] and reviews (startReview/approve/reject) with
 * [reviewer] - never the same identity for both - because [ai.rojan.backend.domain.verification.SalonVerification.startReview]'s
 * own domain guard forbids a reviewer starting review on their own submission
 * (`submittedBy`). This is a real, pre-existing invariant, not a test-only
 * workaround: it means a case one platform actor opens must be picked up by a
 * *different* platform actor to actually decide it - a deliberate separation
 * of duties this test suite exercises correctly rather than routing around.
 */
class PlatformVerificationReviewUseCasesTest {

    private val salonRepository = InMemorySalonRepository()
    private val userRepository = InMemorySalonUserRepository()
    private val platformAuthorization = PlatformAuthorizationResolver(userRepository)
    private val documentRepository = InMemorySalonDocumentRepository()
    private val verificationRepository = InMemorySalonVerificationRepository()
    private val verificationDocumentRepository = InMemorySalonVerificationDocumentRepository()
    private val geoRepository = InMemorySalonGeoClassificationReviewRepository()

    private val initiateReviewUseCase = InitiateRojanReviewUseCase(salonRepository, verificationRepository, platformAuthorization)
    private val listPendingUseCase = ListPendingVerificationsUseCase(verificationRepository, platformAuthorization)
    private val startReviewUseCase = StartVerificationReviewUseCase(verificationRepository, platformAuthorization)
    private val approveUseCase = ApproveSalonVerificationUseCase(
        salonRepository, verificationRepository, verificationDocumentRepository, documentRepository, geoRepository, platformAuthorization,
    )
    private val rejectUseCase = RejectSalonVerificationUseCase(salonRepository, verificationRepository, platformAuthorization)

    private val salon = newSalon().also { salonRepository.save(it) }

    private var phoneCounter = 0
    private fun nextPhone() = PhoneNumber("+1555010${(phoneCounter++).toString().padStart(4, '0')}")

    /** Reviews cases - never initiates one it then reviews itself. */
    private val reviewer = User.registerWithPhone(nextPhone(), "Reviewer One", UserRole.PLATFORM_REVIEWER)
        .also { userRepository.save(it) }

    /** Initiates cases in these tests (an admin can also do everything a reviewer can, per the approved role model - using it as the initiator here is purely to keep it distinct from [reviewer]). */
    private val admin = User.registerWithPhone(nextPhone(), "Admin One", UserRole.PLATFORM_ADMIN)
        .also { userRepository.save(it) }

    private val strangerId = UserId.new().also {
        userRepository.save(User.registerWithPhone(nextPhone(), "Just a Customer", UserRole.CUSTOMER))
    }

    // ---------- authorization: both platform roles admitted, nothing else ----------

    @Test
    fun `both PLATFORM_ADMIN and PLATFORM_REVIEWER can initiate a review, on separate cases`() {
        val first = initiateReviewUseCase.execute(InitiateRojanReviewCommand(salon.id, admin.id))
        startReviewUseCase.execute(StartVerificationReviewCommand(salon.id, first.id, reviewer.id))
        rejectUseCase.execute(RejectSalonVerificationCommand(salon.id, first.id, reviewer.id, "needs another look"))

        val second = initiateReviewUseCase.execute(InitiateRojanReviewCommand(salon.id, reviewer.id))
        assertEquals(reviewer.id, second.submittedBy)
    }

    @Test
    fun `a non-platform caller cannot initiate a review`() {
        assertThrows<PlatformAccessDeniedException> {
            initiateReviewUseCase.execute(InitiateRojanReviewCommand(salon.id, strangerId))
        }
    }

    @Test
    fun `initiating a review while one is already open throws`() {
        initiateReviewUseCase.execute(InitiateRojanReviewCommand(salon.id, admin.id))
        assertThrows<VerificationAlreadyPendingException> {
            initiateReviewUseCase.execute(InitiateRojanReviewCommand(salon.id, admin.id))
        }
    }

    @Test
    fun `a non-platform caller cannot list the pending queue`() {
        assertThrows<PlatformAccessDeniedException> {
            listPendingUseCase.execute(ListPendingVerificationsQuery(strangerId))
        }
    }

    @Test
    fun `the pending queue lists an open case`() {
        initiateReviewUseCase.execute(InitiateRojanReviewCommand(salon.id, admin.id))
        val page = listPendingUseCase.execute(ListPendingVerificationsQuery(reviewer.id))
        assertEquals(1, page.content.size)
        assertEquals(salon.id, page.content.first().salonId)
    }

    // ---------- approve -> rojanVerified projection ----------

    @Test
    fun `approving a verification sets the salon rojanVerified projection`() {
        val verification = initiateReviewUseCase.execute(InitiateRojanReviewCommand(salon.id, admin.id))
        startReviewUseCase.execute(StartVerificationReviewCommand(salon.id, verification.id, reviewer.id))

        approveUseCase.execute(ApproveSalonVerificationCommand(salon.id, verification.id, reviewer.id, qualityScore = 5, decorScore = 4))

        val updated = salonRepository.findById(salon.id)!!
        assertTrue(updated.rojanVerified)
    }

    @Test
    fun `approval requires every linked document to already be individually approved`() {
        val document = SalonDocument.create(salon.id, MediaAssetId.new(), DocumentType.CERTIFICATE, null, salon.ownerId)
            .also { documentRepository.save(it) }
        val verification = initiateReviewUseCase.execute(InitiateRojanReviewCommand(salon.id, admin.id))
        verificationDocumentRepository.saveAll(verification.id, listOf(document.id))
        startReviewUseCase.execute(StartVerificationReviewCommand(salon.id, verification.id, reviewer.id))

        // The document is still PENDING - never individually approved - so the domain's own guard rejects this.
        assertThrows<IllegalArgumentException> {
            approveUseCase.execute(ApproveSalonVerificationCommand(salon.id, verification.id, reviewer.id, qualityScore = 5))
        }
    }

    @Test
    fun `an out-of-range quality score is rejected`() {
        val verification = initiateReviewUseCase.execute(InitiateRojanReviewCommand(salon.id, admin.id))
        startReviewUseCase.execute(StartVerificationReviewCommand(salon.id, verification.id, reviewer.id))

        assertThrows<IllegalArgumentException> {
            approveUseCase.execute(ApproveSalonVerificationCommand(salon.id, verification.id, reviewer.id, qualityScore = 6))
        }
    }

    @Test
    fun `approving records a geo classification review when both fields are supplied`() {
        val verification = initiateReviewUseCase.execute(InitiateRojanReviewCommand(salon.id, admin.id))
        startReviewUseCase.execute(StartVerificationReviewCommand(salon.id, verification.id, reviewer.id))

        approveUseCase.execute(
            ApproveSalonVerificationCommand(salon.id, verification.id, reviewer.id, verifiedNeighborhood = true, verifiedCityCenter = false),
        )

        val geoReview = geoRepository.findByVerificationId(verification.id)
        assertEquals(true, geoReview?.verifiedNeighborhood)
        assertEquals(false, geoReview?.verifiedCityCenter)
    }

    // ---------- reject -> salon stays ACTIVE, projection cleared ----------

    @Test
    fun `rejecting a verification leaves an already-ACTIVE salon ACTIVE and publicly unaffected`() {
        salon.activate()
        salonRepository.save(salon)
        val verification = initiateReviewUseCase.execute(InitiateRojanReviewCommand(salon.id, admin.id))
        startReviewUseCase.execute(StartVerificationReviewCommand(salon.id, verification.id, reviewer.id))

        rejectUseCase.execute(RejectSalonVerificationCommand(salon.id, verification.id, reviewer.id, "decor did not meet the bar"))

        val updated = salonRepository.findById(salon.id)!!
        assertEquals(SalonOnboardingStatus.ACTIVE, updated.onboardingStatus)
        assertTrue(updated.active)
        assertFalse(updated.rojanVerified)
    }

    @Test
    fun `reject requires a non-blank reason`() {
        val verification = initiateReviewUseCase.execute(InitiateRojanReviewCommand(salon.id, admin.id))
        startReviewUseCase.execute(StartVerificationReviewCommand(salon.id, verification.id, reviewer.id))

        assertThrows<IllegalArgumentException> {
            rejectUseCase.execute(RejectSalonVerificationCommand(salon.id, verification.id, reviewer.id, "  "))
        }
    }

    @Test
    fun `a later rejection clears an earlier approval's rojanVerified projection`() {
        val first = initiateReviewUseCase.execute(InitiateRojanReviewCommand(salon.id, admin.id))
        startReviewUseCase.execute(StartVerificationReviewCommand(salon.id, first.id, reviewer.id))
        approveUseCase.execute(ApproveSalonVerificationCommand(salon.id, first.id, reviewer.id, qualityScore = 5))
        assertTrue(salonRepository.findById(salon.id)!!.rojanVerified)

        // The projection recompute orders by createdAt - forces a real gap so `second` is
        // unambiguously newer than `first` even under Windows' coarse Instant.now() resolution,
        // which two Instant.now() calls executed back-to-back in this fast in-memory test can
        // otherwise tie. In real usage a genuine gap always exists (a new case can only open
        // after the prior one is concluded, across separate requests) - this sleep exists purely
        // to make the test's own timing deterministic, not to work around a production bug.
        Thread.sleep(5)
        val second = initiateReviewUseCase.execute(InitiateRojanReviewCommand(salon.id, admin.id))
        startReviewUseCase.execute(StartVerificationReviewCommand(salon.id, second.id, reviewer.id))
        rejectUseCase.execute(RejectSalonVerificationCommand(salon.id, second.id, reviewer.id, "no longer meets the bar"))

        assertFalse(salonRepository.findById(salon.id)!!.rojanVerified)
    }

    // ---------- history preservation ----------

    @Test
    fun `re-review preserves every prior case rather than overwriting it`() {
        val first = initiateReviewUseCase.execute(InitiateRojanReviewCommand(salon.id, admin.id))
        startReviewUseCase.execute(StartVerificationReviewCommand(salon.id, first.id, reviewer.id))
        rejectUseCase.execute(RejectSalonVerificationCommand(salon.id, first.id, reviewer.id, "missing documents"))

        val second = initiateReviewUseCase.execute(InitiateRojanReviewCommand(salon.id, admin.id))
        startReviewUseCase.execute(StartVerificationReviewCommand(salon.id, second.id, reviewer.id))
        approveUseCase.execute(ApproveSalonVerificationCommand(salon.id, second.id, reviewer.id, qualityScore = 4))

        val history = verificationRepository.findHistoryBySalonId(salon.id)
        assertEquals(2, history.size)
        assertTrue(history.any { it.id == first.id })
        assertTrue(history.any { it.id == second.id })
    }

    @Test
    fun `starting review on an unknown case throws`() {
        assertThrows<SalonVerificationNotFoundException> {
            startReviewUseCase.execute(StartVerificationReviewCommand(salon.id, SalonVerificationId.new(), reviewer.id))
        }
    }

    @Test
    fun `a reviewer cannot start review on their own initiated case`() {
        val verification = initiateReviewUseCase.execute(InitiateRojanReviewCommand(salon.id, reviewer.id))
        assertThrows<IllegalArgumentException> {
            startReviewUseCase.execute(StartVerificationReviewCommand(salon.id, verification.id, reviewer.id))
        }
    }
}
