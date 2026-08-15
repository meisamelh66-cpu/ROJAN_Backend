package ai.rojan.backend.infrastructure.persistence.media

import ai.rojan.backend.domain.media.MediaAsset
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.media.MediaAssetRepository
import ai.rojan.backend.domain.media.MediaType
import ai.rojan.backend.domain.salon.SalonId
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class MediaAssetRepositoryAdapter(
    private val jpaRepository: MediaAssetSpringDataRepository,
) : MediaAssetRepository {

    override fun save(mediaAsset: MediaAsset): MediaAsset {
        val entity = jpaRepository.findById(mediaAsset.id.value).orElse(null)
            ?: MediaAssetJpaEntity(
                id = mediaAsset.id.value,
                salonId = mediaAsset.salonId.value,
                ownerType = mediaAsset.ownerType,
                ownerId = mediaAsset.ownerId,
                mediaType = mediaAsset.mediaType,
                storageKey = mediaAsset.storageKey,
                fileName = mediaAsset.fileName,
                mimeType = mediaAsset.mimeType,
                fileSize = mediaAsset.fileSize,
                url = mediaAsset.url,
            )
        // MediaAsset is create-once, immutable metadata after that (no
        // update() on the domain entity) - save() only ever inserts.
        return jpaRepository.save(entity).toDomain()
    }

    override fun findById(id: MediaAssetId): MediaAsset? =
        jpaRepository.findById(id.value).orElse(null)?.toDomain()

    override fun findBySalonId(salonId: SalonId): List<MediaAsset> =
        jpaRepository.findBySalonId(salonId.value).map { it.toDomain() }

    override fun findBySalonIdAndMediaType(salonId: SalonId, mediaType: MediaType): List<MediaAsset> =
        jpaRepository.findBySalonIdAndMediaType(salonId.value, mediaType).map { it.toDomain() }

    override fun delete(id: MediaAssetId) {
        jpaRepository.deleteById(id.value)
    }

    private fun MediaAssetJpaEntity.toDomain(): MediaAsset = MediaAsset.reconstitute(
        id = MediaAssetId(id),
        salonId = SalonId(salonId),
        ownerType = ownerType,
        ownerId = ownerId,
        mediaType = mediaType,
        storageKey = storageKey,
        fileName = fileName,
        mimeType = mimeType,
        fileSize = fileSize,
        url = url,
        createdAt = createdAt ?: Instant.EPOCH,
        updatedAt = updatedAt ?: Instant.EPOCH,
    )
}
