package ai.rojan.backend.application.verification

import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.common.InvalidVerificationDocumentException
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.common.SalonVerificationNotFoundException
import ai.rojan.backend.domain.common.VerificationAlreadyPendingException
import ai.rojan.backend.domain.document.SalonDocumentId
import ai.rojan.backend.domain.document.SalonDocumentRepository
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.verification.SalonVerification
import ai.rojan.backend.domain.verification.SalonVerificationDocumentRepository
import ai.rojan.backend.domain.verification.SalonVerificationRepository

/** A submission's documents, resolved alongside the case itself - [SalonVerification] carries no document-shaped field of its own (see the domain type's own doc comment). */
data class VerificationWithDocuments(
    val verification: SalonVerification,
    val documentIds: List<SalonDocumentId>,
)

data class SubmitVerificationCommand(
    val salonId: SalonId,
    val callerId: UserId,
    val documentIds: List<SalonDocumentId>,
)

/**
 * Submission is an identity check against [ai.rojan.backend.domain.salon.Salon.ownerId],
 * not a permission grant - no staff role, however senior, may submit on
 * the owner's behalf (Phase 3 architecture §04).
 */
class SubmitVerificationUseCase(
    private val salonRepository: SalonRepository,
    private val documentRepository: SalonDocumentRepository,
    private val verificationRepository: SalonVerificationRepository,
    private val verificationDocumentRepository: SalonVerificationDocumentRepository,
) {
    fun execute(command: SubmitVerificationCommand): VerificationWithDocuments {
        val salon = salonRepository.findById(command.salonId) ?: throw SalonNotFoundException(command.salonId.value.toString())
        if (command.callerId != salon.ownerId) {
            throw SalonAccessDeniedException(command.salonId.value.toString())
        }
        require(command.documentIds.isNotEmpty()) { "At least one document is required to submit for verification" }

        if (verificationRepository.findCurrentBySalonId(command.salonId) != null) {
            throw VerificationAlreadyPendingException(command.salonId.value.toString())
        }
        command.documentIds.forEach { documentId ->
            documentRepository.findByIdAndSalonId(documentId, command.salonId)
                ?: throw InvalidVerificationDocumentException(documentId.value.toString())
        }

        val verification = verificationRepository.save(SalonVerification.create(command.salonId, command.callerId))
        verificationDocumentRepository.saveAll(verification.id, command.documentIds)
        return VerificationWithDocuments(verification, command.documentIds)
    }
}

class GetVerificationUseCase(
    private val salonRepository: SalonRepository,
    private val verificationRepository: SalonVerificationRepository,
    private val verificationDocumentRepository: SalonVerificationDocumentRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(salonId: SalonId, callerId: UserId): VerificationWithDocuments {
        salonRepository.findById(salonId) ?: throw SalonNotFoundException(salonId.value.toString())
        salonPermissionResolver.requireAny(salonId, callerId, Permission.VIEW_DOCUMENTS, Permission.MANAGE_DOCUMENTS)
        val verification = verificationRepository.findCurrentBySalonId(salonId)
            ?: throw SalonVerificationNotFoundException(salonId.value.toString())
        return VerificationWithDocuments(verification, verificationDocumentRepository.findDocumentIdsByVerificationId(verification.id))
    }
}

/** Owner-only, unlike [GetVerificationUseCase] - the full case-by-case trail is a step more sensitive than "what's the current status". */
class ListVerificationHistoryUseCase(
    private val salonRepository: SalonRepository,
    private val verificationRepository: SalonVerificationRepository,
    private val verificationDocumentRepository: SalonVerificationDocumentRepository,
) {
    fun execute(salonId: SalonId, callerId: UserId): List<VerificationWithDocuments> {
        val salon = salonRepository.findById(salonId) ?: throw SalonNotFoundException(salonId.value.toString())
        if (callerId != salon.ownerId) {
            throw SalonAccessDeniedException(salonId.value.toString())
        }
        return verificationRepository.findHistoryBySalonId(salonId).map { verification ->
            VerificationWithDocuments(verification, verificationDocumentRepository.findDocumentIdsByVerificationId(verification.id))
        }
    }
}
