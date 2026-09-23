package ai.rojan.backend.application.document

import ai.rojan.backend.application.platformauthority.PlatformAuthorizationResolver
import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.application.salon.InMemorySalonUserRepository
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.PlatformAccessDeniedException
import ai.rojan.backend.domain.common.SalonDocumentNotFoundException
import ai.rojan.backend.domain.document.DocumentType
import ai.rojan.backend.domain.document.DocumentVerificationStatus
import ai.rojan.backend.domain.document.SalonDocument
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonOnboardingStatus
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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

/** Covers [ApproveSalonDocumentUseCase]/[RejectSalonDocumentUseCase] - platform-scoped document review, hygiene certificates included. */
class DocumentReviewUseCasesTest {

    private val salonRepository = InMemorySalonRepository()
    private val documentRepository = InMemorySalonDocumentRepository()
    private val userRepository = InMemorySalonUserRepository()
    private val platformAuthorization = PlatformAuthorizationResolver(userRepository)

    private val approveUseCase = ApproveSalonDocumentUseCase(documentRepository, platformAuthorization)
    private val rejectUseCase = RejectSalonDocumentUseCase(documentRepository, platformAuthorization)

    private val salon = newSalon().also { salonRepository.save(it) }
    private var phoneCounter = 0
    private fun nextPhone() = PhoneNumber("+1555020${(phoneCounter++).toString().padStart(4, '0')}")

    private val reviewer = User.registerWithPhone(nextPhone(), "Reviewer One", UserRole.PLATFORM_REVIEWER)
        .also { userRepository.save(it) }
    private val strangerId = UserId.new().also {
        userRepository.save(User.registerWithPhone(nextPhone(), "Just a Manager", UserRole.MANAGER))
    }

    private fun hygieneCertificateFor(specialistId: SpecialistId) =
        SalonDocument.create(salon.id, MediaAssetId.new(), DocumentType.HYGIENE_CERTIFICATE, null, salon.ownerId, specialistId)
            .also { documentRepository.save(it) }

    @Test
    fun `a platform reviewer can approve a hygiene certificate and the reviewer identity is recorded`() {
        val certificate = hygieneCertificateFor(SpecialistId.new())

        val approved = approveUseCase.execute(ApproveSalonDocumentCommand(salon.id, certificate.id, reviewer.id))

        assertEquals(DocumentVerificationStatus.APPROVED, approved.verificationStatus)
        assertEquals(reviewer.id, approved.reviewedBy)
        assertNotNull(approved.reviewedAt)
    }

    @Test
    fun `a platform reviewer can reject a hygiene certificate and the reviewer identity is recorded`() {
        val certificate = hygieneCertificateFor(SpecialistId.new())

        val rejected = rejectUseCase.execute(RejectSalonDocumentCommand(salon.id, certificate.id, reviewer.id, "photo is illegible"))

        assertEquals(DocumentVerificationStatus.REJECTED, rejected.verificationStatus)
        assertEquals(reviewer.id, rejected.reviewedBy)
        assertNotNull(rejected.reviewedAt)
    }

    @Test
    fun `rejecting a hygiene certificate never deactivates or unpublishes the salon`() {
        salon.activate()
        salonRepository.save(salon)
        val certificate = hygieneCertificateFor(SpecialistId.new())

        rejectUseCase.execute(RejectSalonDocumentCommand(salon.id, certificate.id, reviewer.id, "expired"))

        val stillActive = salonRepository.findById(salon.id)!!
        assertEquals(SalonOnboardingStatus.ACTIVE, stillActive.onboardingStatus)
        assertTrue(stillActive.active)
    }

    @Test
    fun `a non-platform caller cannot approve a document`() {
        val certificate = hygieneCertificateFor(SpecialistId.new())
        assertThrows<PlatformAccessDeniedException> {
            approveUseCase.execute(ApproveSalonDocumentCommand(salon.id, certificate.id, strangerId))
        }
    }

    @Test
    fun `approving an unknown document throws`() {
        assertThrows<SalonDocumentNotFoundException> {
            approveUseCase.execute(ApproveSalonDocumentCommand(salon.id, ai.rojan.backend.domain.document.SalonDocumentId.new(), reviewer.id))
        }
    }
}
