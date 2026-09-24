package ai.rojan.backend.domain.document

import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SpecialistId

interface SalonDocumentRepository {
    fun save(document: SalonDocument): SalonDocument

    /** Tenant-scoped by construction - callers must always resolve through this, never a bare id lookup, so a cross-salon reference can 404 rather than leak. */
    fun findByIdAndSalonId(id: SalonDocumentId, salonId: SalonId): SalonDocument?

    fun findBySalonId(
        salonId: SalonId,
        documentType: DocumentType? = null,
        verificationStatus: DocumentVerificationStatus? = null,
    ): List<SalonDocument>

    /** Staff Hygiene Certificates (V31) - every document (of any type) attached to one specialist, optionally narrowed the same way [findBySalonId] already is. In practice callers pass [DocumentType.HYGIENE_CERTIFICATE] to find that specialist's certificates. */
    fun findBySpecialistId(
        specialistId: SpecialistId,
        documentType: DocumentType? = null,
        verificationStatus: DocumentVerificationStatus? = null,
    ): List<SalonDocument>

    /** Enforces the 1:1 document-to-media relationship at the application layer, on top of the database's own unique constraint (§05). */
    fun findByMediaAssetId(mediaAssetId: MediaAssetId): SalonDocument?

    fun deleteById(id: SalonDocumentId)
}
