package ai.rojan.backend.infrastructure.persistence.media

import ai.rojan.backend.domain.media.MediaAsset
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.media.MediaAssetRepository
import ai.rojan.backend.domain.media.MediaType
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/** Repository-pattern adapter: implements the domain [MediaAssetRepository] port on top of Spring Data JPA. */
@Repository
class MediaAssetRepositoryAdapter(
    private val jpaRepository: MediaAssetSpringDataRepository,
) : MediaAssetRepository {

    override fun save(mediaAsset: MediaAsset): MediaAsset {
        val entity = jpaRepository.findById(mediaAsset.id.value).orElse(null)
            ?.apply {
                mediaType = mediaAsset.mediaType
                storageKey = mediaAsset.storageKey
                status = mediaAsset.status
                displayOrder = mediaAsset.displayOrder
            }
            ?: MediaAssetJpaEntity(
                id = mediaAsset.id.value,
                salonId = mediaAsset.salonId.value,
                mediaType = mediaAsset.mediaType,
                storageKey = mediaAsset.storageKey,
                originalName = mediaAsset.originalName,
                mimeType = mediaAsset.mimeType,
                fileSize = mediaAsset.fileSize,
                status = mediaAsset.status,
                uploadedBy = mediaAsset.uploadedBy.value,
                targetId = mediaAsset.targetId,
                displayOrder = mediaAsset.displayOrder,
            )
        return jpaRepository.save(entity).toDomain()
    }

    override fun findByIdAndSalonId(id: MediaAssetId, salonId: SalonId): MediaAsset? =
        jpaRepository.findByIdAndSalonId(id.value, salonId.value)?.toDomain()

    override fun findBySalonId(salonId: SalonId, mediaType: MediaType?, targetId: UUID?): List<MediaAsset> =
        jpaRepository.findBySalonId(salonId.value)
            .filter { mediaType == null || it.mediaType == mediaType }
            .filter { targetId == null || it.targetId == targetId }
            .map { it.toDomain() }
            .sortedWith(compareBy({ it.displayOrder }, { it.createdAt }))

    private fun MediaAssetJpaEntity.toDomain(): MediaAsset = MediaAsset.reconstitute(
        id = MediaAssetId(id),
        salonId = SalonId(salonId),
        mediaType = mediaType,
        storageKey = storageKey,
        originalName = originalName,
        mimeType = mimeType,
        fileSize = fileSize,
        status = status,
        uploadedBy = UserId(uploadedBy),
        targetId = targetId,
        displayOrder = displayOrder,
        createdAt = createdAt ?: Instant.EPOCH,
        updatedAt = updatedAt ?: Instant.EPOCH,
    )
}
