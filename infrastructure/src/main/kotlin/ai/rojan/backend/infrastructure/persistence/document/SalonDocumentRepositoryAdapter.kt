package ai.rojan.backend.infrastructure.persistence.document

import ai.rojan.backend.domain.document.DocumentType
import ai.rojan.backend.domain.document.DocumentVerificationStatus
import ai.rojan.backend.domain.document.SalonDocument
import ai.rojan.backend.domain.document.SalonDocumentId
import ai.rojan.backend.domain.document.SalonDocumentRepository
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId
import org.springframework.stereotype.Repository
import java.time.Instant

/** Repository-pattern adapter: implements the domain [SalonDocumentRepository] port on top of Spring Data JPA. */
@Repository
class SalonDocumentRepositoryAdapter(
    private val jpaRepository: SalonDocumentSpringDataRepository,
) : SalonDocumentRepository {

    override fun save(document: SalonDocument): SalonDocument {
        val entity = jpaRepository.findById(document.id.value).orElse(null)
            ?.apply { verificationStatus = document.verificationStatus }
            ?: SalonDocumentJpaEntity(
                id = document.id.value,
                salonId = document.salonId.value,
                mediaAssetId = document.mediaAssetId.value,
                documentType = document.documentType,
                verificationStatus = document.verificationStatus,
                expiryDate = document.expiryDate,
                uploadedBy = document.uploadedBy.value,
            )
        return jpaRepository.save(entity).toDomain()
    }

    override fun findByIdAndSalonId(id: SalonDocumentId, salonId: SalonId): SalonDocument? =
        jpaRepository.findByIdAndSalonId(id.value, salonId.value)?.toDomain()

    override fun findBySalonId(salonId: SalonId, documentType: DocumentType?, verificationStatus: DocumentVerificationStatus?): List<SalonDocument> =
        jpaRepository.findBySalonId(salonId.value)
            .filter { (documentType == null || it.documentType == documentType) && (verificationStatus == null || it.verificationStatus == verificationStatus) }
            .map { it.toDomain() }

    override fun findByMediaAssetId(mediaAssetId: MediaAssetId): SalonDocument? =
        jpaRepository.findByMediaAssetId(mediaAssetId.value)?.toDomain()

    override fun deleteById(id: SalonDocumentId) {
        jpaRepository.deleteById(id.value)
    }

    private fun SalonDocumentJpaEntity.toDomain(): SalonDocument = SalonDocument.reconstitute(
        id = SalonDocumentId(id),
        salonId = SalonId(salonId),
        mediaAssetId = MediaAssetId(mediaAssetId),
        documentType = documentType,
        verificationStatus = verificationStatus,
        expiryDate = expiryDate,
        uploadedBy = UserId(uploadedBy),
        createdAt = createdAt ?: Instant.EPOCH,
        updatedAt = updatedAt ?: Instant.EPOCH,
    )
}
