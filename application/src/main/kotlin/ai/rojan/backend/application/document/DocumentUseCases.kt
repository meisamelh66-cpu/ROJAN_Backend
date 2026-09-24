package ai.rojan.backend.application.document

import ai.rojan.backend.application.media.DeleteMediaCommand
import ai.rojan.backend.application.media.DeleteMediaUseCase
import ai.rojan.backend.application.platformauthority.PlatformAuthorizationResolver
import ai.rojan.backend.application.port.MediaStoragePort
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.common.DocumentAlreadyAttachedException
import ai.rojan.backend.domain.common.MediaAssetNotFoundException
import ai.rojan.backend.domain.common.MediaTypeMismatchException
import ai.rojan.backend.domain.common.SalonDocumentNotFoundException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.document.DocumentType
import ai.rojan.backend.domain.document.DocumentVerificationStatus
import ai.rojan.backend.domain.document.SalonDocument
import ai.rojan.backend.domain.document.SalonDocumentId
import ai.rojan.backend.domain.document.SalonDocumentRepository
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.media.MediaAssetRepository
import ai.rojan.backend.domain.media.MediaType
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId
import java.time.Instant
import java.time.LocalDate

/** Document access is a 5-minute signed link, never a stored value - see [GetDocumentAccessUrlUseCase]. */
private const val ACCESS_URL_EXPIRY_SECONDS = 300L

data class AttachDocumentCommand(
    val salonId: SalonId,
    val callerId: UserId,
    val mediaAssetId: MediaAssetId,
    val documentType: DocumentType,
    val expiryDate: LocalDate?,
)

/**
 * Attaches [SalonDocument] metadata to an already-uploaded, `DOCUMENT`-typed
 * [ai.rojan.backend.domain.media.MediaAsset] - never uploads bytes itself,
 * that already happened via the existing `POST /salons/{salonId}/media`
 * (reused, not duplicated).
 */
class AttachDocumentUseCase(
    private val salonRepository: SalonRepository,
    private val mediaAssetRepository: MediaAssetRepository,
    private val documentRepository: SalonDocumentRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: AttachDocumentCommand): SalonDocument {
        salonRepository.findById(command.salonId) ?: throw SalonNotFoundException(command.salonId.value.toString())
        salonPermissionResolver.require(command.salonId, command.callerId, Permission.MANAGE_DOCUMENTS)

        val mediaAsset = mediaAssetRepository.findByIdAndSalonId(command.mediaAssetId, command.salonId)
            ?: throw MediaAssetNotFoundException(command.mediaAssetId.value.toString())
        if (mediaAsset.mediaType != MediaType.DOCUMENT) {
            throw MediaTypeMismatchException(mediaAsset.id.value.toString(), MediaType.DOCUMENT.name)
        }
        if (documentRepository.findByMediaAssetId(command.mediaAssetId) != null) {
            throw DocumentAlreadyAttachedException(command.mediaAssetId.value.toString())
        }

        val document = SalonDocument.create(
            salonId = command.salonId,
            mediaAssetId = command.mediaAssetId,
            documentType = command.documentType,
            expiryDate = command.expiryDate,
            uploadedBy = command.callerId,
        )
        return documentRepository.save(document)
    }
}

data class ListDocumentsQuery(
    val salonId: SalonId,
    val callerId: UserId,
    val documentType: DocumentType?,
    val verificationStatus: DocumentVerificationStatus?,
)

class ListDocumentsUseCase(
    private val salonRepository: SalonRepository,
    private val documentRepository: SalonDocumentRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(query: ListDocumentsQuery): List<SalonDocument> {
        salonRepository.findById(query.salonId) ?: throw SalonNotFoundException(query.salonId.value.toString())
        salonPermissionResolver.requireAny(query.salonId, query.callerId, Permission.VIEW_DOCUMENTS, Permission.MANAGE_DOCUMENTS)
        return documentRepository.findBySalonId(query.salonId, query.documentType, query.verificationStatus)
    }
}

class GetDocumentUseCase(
    private val salonRepository: SalonRepository,
    private val documentRepository: SalonDocumentRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(salonId: SalonId, callerId: UserId, documentId: SalonDocumentId): SalonDocument {
        salonRepository.findById(salonId) ?: throw SalonNotFoundException(salonId.value.toString())
        salonPermissionResolver.requireAny(salonId, callerId, Permission.VIEW_DOCUMENTS, Permission.MANAGE_DOCUMENTS)
        return documentRepository.findByIdAndSalonId(documentId, salonId)
            ?: throw SalonDocumentNotFoundException(documentId.value.toString())
    }
}

data class DocumentAccessUrl(val url: String, val expiresAt: Instant)

/**
 * The only way a document's bytes are ever fetched - Security Gate §10.2:
 * a fresh, 5-minute, GET-only signed link, minted on every call, never
 * cached or reused across requests.
 */
class GetDocumentAccessUrlUseCase(
    private val salonRepository: SalonRepository,
    private val documentRepository: SalonDocumentRepository,
    private val mediaAssetRepository: MediaAssetRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
    private val mediaStoragePort: MediaStoragePort,
) {
    fun execute(salonId: SalonId, callerId: UserId, documentId: SalonDocumentId): DocumentAccessUrl {
        salonRepository.findById(salonId) ?: throw SalonNotFoundException(salonId.value.toString())
        salonPermissionResolver.requireAny(salonId, callerId, Permission.VIEW_DOCUMENTS, Permission.MANAGE_DOCUMENTS)
        val document = documentRepository.findByIdAndSalonId(documentId, salonId)
            ?: throw SalonDocumentNotFoundException(documentId.value.toString())
        val mediaAsset = mediaAssetRepository.findByIdAndSalonId(document.mediaAssetId, salonId)
            ?: throw MediaAssetNotFoundException(document.mediaAssetId.value.toString())

        val url = mediaStoragePort.resolveSignedUrl(mediaAsset.storageKey, ACCESS_URL_EXPIRY_SECONDS)
        return DocumentAccessUrl(url, Instant.now().plusSeconds(ACCESS_URL_EXPIRY_SECONDS))
    }
}

data class DeleteDocumentCommand(
    val salonId: SalonId,
    val callerId: UserId,
    val documentId: SalonDocumentId,
)

/**
 * Reuses [DeleteMediaUseCase] wholesale for the underlying file (soft
 * -deletes the `MediaAsset`, clears any dangling identity-slot assignment
 * - harmless no-op for a DOCUMENT-typed asset, which is never assigned as
 * a logo/cover) rather than re-implementing file deletion here. The
 * `SalonDocument` row itself is a hard delete - the compliance-relevant
 * artifact retained for audit purposes is the file (via `MediaAsset`'s
 * soft-delete), not this metadata pointer.
 */
class DeleteDocumentUseCase(
    private val salonRepository: SalonRepository,
    private val documentRepository: SalonDocumentRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
    private val deleteMediaUseCase: DeleteMediaUseCase,
) {
    fun execute(command: DeleteDocumentCommand) {
        salonRepository.findById(command.salonId) ?: throw SalonNotFoundException(command.salonId.value.toString())
        salonPermissionResolver.require(command.salonId, command.callerId, Permission.MANAGE_DOCUMENTS)
        val document = documentRepository.findByIdAndSalonId(command.documentId, command.salonId)
            ?: throw SalonDocumentNotFoundException(command.documentId.value.toString())

        deleteMediaUseCase.execute(DeleteMediaCommand(command.salonId, command.callerId, document.mediaAssetId))
        documentRepository.deleteById(document.id)
    }
}

// ---- Platform Authority document review (Phase 4, Staff Hygiene Certificates + general documents) ----

data class ApproveSalonDocumentCommand(val salonId: SalonId, val documentId: SalonDocumentId, val reviewerId: UserId)

/**
 * Platform-scoped, not salon-scoped - authorization is
 * [PlatformAuthorizationResolver.requirePlatformReviewerOrAdmin], never
 * [SalonPermissionResolver]. Reviewed individually per document (a hygiene
 * certificate included) - approving one document never touches any other
 * document, [ai.rojan.backend.domain.verification.SalonVerification], or
 * [ai.rojan.backend.domain.salon.Salon.active]/[ai.rojan.backend.domain.salon.Salon.onboardingStatus].
 */
class ApproveSalonDocumentUseCase(
    private val documentRepository: SalonDocumentRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(command: ApproveSalonDocumentCommand): SalonDocument {
        platformAuthorization.requirePlatformReviewerOrAdmin(command.reviewerId)
        val document = documentRepository.findByIdAndSalonId(command.documentId, command.salonId)
            ?: throw SalonDocumentNotFoundException(command.documentId.value.toString())
        document.approve(command.reviewerId)
        return documentRepository.save(document)
    }
}

data class RejectSalonDocumentCommand(val salonId: SalonId, val documentId: SalonDocumentId, val reviewerId: UserId, val reason: String)

/** Same authorization/isolation shape as [ApproveSalonDocumentUseCase]. A rejected hygiene certificate never deactivates the salon - nothing here touches [ai.rojan.backend.domain.salon.Salon] at all. */
class RejectSalonDocumentUseCase(
    private val documentRepository: SalonDocumentRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(command: RejectSalonDocumentCommand): SalonDocument {
        platformAuthorization.requirePlatformReviewerOrAdmin(command.reviewerId)
        val document = documentRepository.findByIdAndSalonId(command.documentId, command.salonId)
            ?: throw SalonDocumentNotFoundException(command.documentId.value.toString())
        document.reject(command.reviewerId, command.reason)
        return documentRepository.save(document)
    }
}

data class ListSalonDocumentsForPlatformQuery(
    val salonId: SalonId,
    val callerId: UserId,
    val documentType: DocumentType?,
    val verificationStatus: DocumentVerificationStatus?,
)

/**
 * API contract-completion phase: Platform Authority counterpart to [ListDocumentsUseCase] - the
 * exact same list (every document type, [DocumentType.HYGIENE_CERTIFICATE] included), authorized via
 * [PlatformAuthorizationResolver] instead of [ai.rojan.backend.application.salon.SalonPermissionResolver].
 * Reuses [SalonDocumentRepository.findBySalonId] - no new repository method.
 */
class ListSalonDocumentsForPlatformUseCase(
    private val salonRepository: SalonRepository,
    private val documentRepository: SalonDocumentRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(query: ListSalonDocumentsForPlatformQuery): List<SalonDocument> {
        platformAuthorization.requirePlatformReviewerOrAdmin(query.callerId)
        salonRepository.findById(query.salonId) ?: throw SalonNotFoundException(query.salonId.value.toString())
        return documentRepository.findBySalonId(query.salonId, query.documentType, query.verificationStatus)
    }
}
