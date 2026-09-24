package ai.rojan.backend.application.document

import ai.rojan.backend.domain.document.DocumentType
import ai.rojan.backend.domain.document.DocumentVerificationStatus
import ai.rojan.backend.domain.document.SalonDocument
import ai.rojan.backend.domain.document.SalonDocumentId
import ai.rojan.backend.domain.document.SalonDocumentRepository
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SpecialistId

/** Mirrors [ai.rojan.backend.application.salon.InMemorySalonRepository]'s style. */
internal class InMemorySalonDocumentRepository : SalonDocumentRepository {
    private val store = mutableMapOf<SalonDocumentId, SalonDocument>()

    override fun save(document: SalonDocument): SalonDocument = document.also { store[it.id] = it }

    override fun findByIdAndSalonId(id: SalonDocumentId, salonId: SalonId): SalonDocument? =
        store[id]?.takeIf { it.salonId == salonId }

    override fun findBySalonId(salonId: SalonId, documentType: DocumentType?, verificationStatus: DocumentVerificationStatus?): List<SalonDocument> =
        store.values.filter {
            it.salonId == salonId &&
                (documentType == null || it.documentType == documentType) &&
                (verificationStatus == null || it.verificationStatus == verificationStatus)
        }

    override fun findBySpecialistId(specialistId: SpecialistId, documentType: DocumentType?, verificationStatus: DocumentVerificationStatus?): List<SalonDocument> =
        store.values.filter {
            it.specialistId == specialistId &&
                (documentType == null || it.documentType == documentType) &&
                (verificationStatus == null || it.verificationStatus == verificationStatus)
        }

    override fun findByMediaAssetId(mediaAssetId: MediaAssetId): SalonDocument? =
        store.values.find { it.mediaAssetId == mediaAssetId }

    override fun deleteById(id: SalonDocumentId) {
        store.remove(id)
    }
}
