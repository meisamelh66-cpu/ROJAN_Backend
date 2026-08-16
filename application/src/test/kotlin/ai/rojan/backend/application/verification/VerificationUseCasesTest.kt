package ai.rojan.backend.application.verification

import ai.rojan.backend.application.document.InMemorySalonDocumentRepository
import ai.rojan.backend.application.salon.InMemorySalonMembershipRepository
import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.application.salon.InMemorySpecialistRepository
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.common.InvalidVerificationDocumentException
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.common.SalonVerificationNotFoundException
import ai.rojan.backend.domain.common.VerificationAlreadyPendingException
import ai.rojan.backend.domain.document.DocumentType
import ai.rojan.backend.domain.document.SalonDocument
import ai.rojan.backend.domain.document.SalonDocumentId
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRole
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.verification.SalonVerificationStatus
import org.junit.jupiter.api.Assertions.assertEquals
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

class VerificationUseCasesTest {

    private val salonRepository = InMemorySalonRepository()
    private val membershipRepository = InMemorySalonMembershipRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val salonPermissionResolver = SalonPermissionResolver(salonRepository, membershipRepository, specialistRepository)
    private val documentRepository = InMemorySalonDocumentRepository()
    private val verificationRepository = InMemorySalonVerificationRepository()
    private val verificationDocumentRepository = InMemorySalonVerificationDocumentRepository()

    private val submitVerificationUseCase = SubmitVerificationUseCase(salonRepository, documentRepository, verificationRepository, verificationDocumentRepository)
    private val getVerificationUseCase = GetVerificationUseCase(salonRepository, verificationRepository, verificationDocumentRepository, salonPermissionResolver)
    private val listVerificationHistoryUseCase = ListVerificationHistoryUseCase(salonRepository, verificationRepository, verificationDocumentRepository)

    private val ownerId = UserId.new()
    private val salon = newSalon(ownerId).also { salonRepository.save(it) }

    /** A Manager has MANAGE_MEDIA/VIEW_DOCUMENTS-adjacent access but no submission authority - the exact "unauthorized" case worth exercising, not just a total stranger. */
    private val managerId = UserId.new().also { membershipRepository.assign(salon.id, it, SalonRole.MANAGER) }

    private val strangerId = UserId.new()

    private fun documentFor(salonId: SalonId = salon.id): SalonDocumentId {
        val asset = SalonDocument.create(salonId, MediaAssetId.new(), DocumentType.LICENSE, null, ownerId)
        return documentRepository.save(asset).id
    }

    // ---------- submit ----------

    @Test
    fun `owner can submit a verification with valid documents`() {
        val documentId = documentFor()

        val result = submitVerificationUseCase.execute(SubmitVerificationCommand(salon.id, ownerId, listOf(documentId)))

        assertEquals(SalonVerificationStatus.PENDING, result.verification.status)
        assertEquals(listOf(documentId), result.documentIds)
    }

    @Test
    fun `submit for a nonexistent salon throws`() {
        assertThrows<SalonNotFoundException> {
            submitVerificationUseCase.execute(SubmitVerificationCommand(SalonId.new(), ownerId, listOf(documentFor())))
        }
    }

    @Test
    fun `manager cannot submit - submission is an owner-identity check, not a permission grant`() {
        val documentId = documentFor()
        assertThrows<SalonAccessDeniedException> {
            submitVerificationUseCase.execute(SubmitVerificationCommand(salon.id, managerId, listOf(documentId)))
        }
    }

    @Test
    fun `a stranger cannot submit`() {
        val documentId = documentFor()
        assertThrows<SalonAccessDeniedException> {
            submitVerificationUseCase.execute(SubmitVerificationCommand(salon.id, strangerId, listOf(documentId)))
        }
    }

    @Test
    fun `submit rejects a document belonging to another salon`() {
        val otherSalon = newSalon().also { salonRepository.save(it) }
        val foreignDocumentId = documentFor(otherSalon.id)

        assertThrows<InvalidVerificationDocumentException> {
            submitVerificationUseCase.execute(SubmitVerificationCommand(salon.id, ownerId, listOf(foreignDocumentId)))
        }
    }

    @Test
    fun `submit rejects an unknown document id`() {
        assertThrows<InvalidVerificationDocumentException> {
            submitVerificationUseCase.execute(SubmitVerificationCommand(salon.id, ownerId, listOf(SalonDocumentId.new())))
        }
    }

    @Test
    fun `submit rejects an empty document list`() {
        assertThrows<IllegalArgumentException> {
            submitVerificationUseCase.execute(SubmitVerificationCommand(salon.id, ownerId, emptyList()))
        }
    }

    @Test
    fun `submit rejects a second case while one is already active`() {
        val documentId = documentFor()
        submitVerificationUseCase.execute(SubmitVerificationCommand(salon.id, ownerId, listOf(documentId)))

        assertThrows<VerificationAlreadyPendingException> {
            submitVerificationUseCase.execute(SubmitVerificationCommand(salon.id, ownerId, listOf(documentId)))
        }
    }

    // ---------- get ----------

    @Test
    fun `owner can get the current verification status`() {
        val documentId = documentFor()
        submitVerificationUseCase.execute(SubmitVerificationCommand(salon.id, ownerId, listOf(documentId)))

        val result = getVerificationUseCase.execute(salon.id, ownerId)

        assertEquals(SalonVerificationStatus.PENDING, result.verification.status)
    }

    @Test
    fun `manager cannot get the verification status - no SalonRole currently grants VIEW_DOCUMENTS or MANAGE_DOCUMENTS`() {
        val documentId = documentFor()
        submitVerificationUseCase.execute(SubmitVerificationCommand(salon.id, ownerId, listOf(documentId)))

        assertThrows<SalonAccessDeniedException> {
            getVerificationUseCase.execute(salon.id, managerId)
        }
    }

    @Test
    fun `a stranger cannot get the verification status`() {
        val documentId = documentFor()
        submitVerificationUseCase.execute(SubmitVerificationCommand(salon.id, ownerId, listOf(documentId)))

        assertThrows<SalonAccessDeniedException> {
            getVerificationUseCase.execute(salon.id, strangerId)
        }
    }

    @Test
    fun `getting status for a salon that never submitted throws`() {
        assertThrows<SalonVerificationNotFoundException> {
            getVerificationUseCase.execute(salon.id, ownerId)
        }
    }

    @Test
    fun `a cross-salon lookup 404s rather than leaking another salon's case`() {
        val otherSalon = newSalon().also { salonRepository.save(it) }
        val documentId = documentFor(otherSalon.id)
        submitVerificationUseCase.execute(SubmitVerificationCommand(otherSalon.id, otherSalon.ownerId, listOf(documentId)))

        assertThrows<SalonVerificationNotFoundException> {
            getVerificationUseCase.execute(salon.id, ownerId)
        }
    }

    // ---------- history ----------

    @Test
    fun `owner sees the full history newest first`() {
        val documentId = documentFor()
        val first = submitVerificationUseCase.execute(SubmitVerificationCommand(salon.id, ownerId, listOf(documentId)))

        val history = listVerificationHistoryUseCase.execute(salon.id, ownerId)

        assertEquals(1, history.size)
        assertEquals(first.verification.id, history.first().verification.id)
    }

    @Test
    fun `manager cannot list history`() {
        assertThrows<SalonAccessDeniedException> {
            listVerificationHistoryUseCase.execute(salon.id, managerId)
        }
    }

    @Test
    fun `history returns each case with its own linked documents`() {
        val documentId = documentFor()
        submitVerificationUseCase.execute(SubmitVerificationCommand(salon.id, ownerId, listOf(documentId)))

        val history = listVerificationHistoryUseCase.execute(salon.id, ownerId)

        assertTrue(history.single().documentIds.contains(documentId))
    }

    @Test
    fun `history for an unknown salon throws`() {
        assertThrows<SalonNotFoundException> {
            listVerificationHistoryUseCase.execute(SalonId.new(), ownerId)
        }
    }
}
