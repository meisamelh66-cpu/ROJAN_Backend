package ai.rojan.backend.domain.document

import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@JvmInline
value class SalonDocumentId(val value: UUID) {
    companion object {
        fun new(): SalonDocumentId = SalonDocumentId(UUID.randomUUID())
    }
}

enum class DocumentType { LICENSE, CERTIFICATE, OWNERSHIP, AGREEMENT, OTHER }

enum class DocumentVerificationStatus { PENDING, APPROVED, REJECTED, EXPIRED }

/**
 * Compliance-facing metadata layered on top of a `DOCUMENT`-typed
 * [ai.rojan.backend.domain.media.MediaAsset] - composition, not
 * duplication. This carries no file-shaped field (no filename, mime
 * type, size, storage key) - all of that already lives on the
 * [mediaAssetId] row it references. [verificationStatus] is an
 * independent state machine from both [ai.rojan.backend.domain.media.MediaAssetStatus]
 * (file lifecycle) and [ai.rojan.backend.domain.salon.SalonOnboardingStatus]
 * (salon lifecycle) - never merged with either.
 */
class SalonDocument private constructor(
    val id: SalonDocumentId,
    val salonId: SalonId,
    val mediaAssetId: MediaAssetId,
    val documentType: DocumentType,
    verificationStatus: DocumentVerificationStatus,
    val expiryDate: LocalDate?,
    val uploadedBy: UserId,
    val createdAt: Instant,
    updatedAt: Instant,
) {
    var verificationStatus: DocumentVerificationStatus = verificationStatus
        private set

    var updatedAt: Instant = updatedAt
        private set

    /** Platform Authority tooling only (not built this phase) - the guard exists now so the invariant is real the moment a caller exists, not bolted on later. */
    fun approve() {
        require(verificationStatus == DocumentVerificationStatus.PENDING) { "Only a pending document can be approved" }
        verificationStatus = DocumentVerificationStatus.APPROVED
        touch()
    }

    fun reject(reason: String) {
        require(verificationStatus == DocumentVerificationStatus.PENDING) { "Only a pending document can be rejected" }
        require(reason.isNotBlank()) { "A rejection reason is required" }
        verificationStatus = DocumentVerificationStatus.REJECTED
        touch()
    }

    /** Scheduled-job territory (no scheduler exists yet, per the architecture's own risk register) - not request-time. */
    fun expire() {
        require(verificationStatus == DocumentVerificationStatus.APPROVED) { "Only an approved document can expire" }
        verificationStatus = DocumentVerificationStatus.EXPIRED
        touch()
    }

    private fun touch() {
        updatedAt = Instant.now()
    }

    companion object {
        fun create(
            salonId: SalonId,
            mediaAssetId: MediaAssetId,
            documentType: DocumentType,
            expiryDate: LocalDate?,
            uploadedBy: UserId,
        ): SalonDocument {
            val now = Instant.now()
            return SalonDocument(
                id = SalonDocumentId.new(),
                salonId = salonId,
                mediaAssetId = mediaAssetId,
                documentType = documentType,
                verificationStatus = DocumentVerificationStatus.PENDING,
                expiryDate = expiryDate,
                uploadedBy = uploadedBy,
                createdAt = now,
                updatedAt = now,
            )
        }

        fun reconstitute(
            id: SalonDocumentId,
            salonId: SalonId,
            mediaAssetId: MediaAssetId,
            documentType: DocumentType,
            verificationStatus: DocumentVerificationStatus,
            expiryDate: LocalDate?,
            uploadedBy: UserId,
            createdAt: Instant,
            updatedAt: Instant,
        ): SalonDocument = SalonDocument(
            id, salonId, mediaAssetId, documentType, verificationStatus, expiryDate, uploadedBy, createdAt, updatedAt,
        )
    }
}
