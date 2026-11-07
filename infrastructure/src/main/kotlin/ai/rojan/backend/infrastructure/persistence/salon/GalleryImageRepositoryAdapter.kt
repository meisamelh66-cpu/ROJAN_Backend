package ai.rojan.backend.infrastructure.persistence.salon

import ai.rojan.backend.domain.salon.GalleryImage
import ai.rojan.backend.domain.salon.GalleryImageId
import ai.rojan.backend.domain.salon.GalleryImageRepository
import ai.rojan.backend.domain.salon.SalonId
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class GalleryImageRepositoryAdapter(
    private val jpaRepository: GalleryImageSpringDataRepository,
) : GalleryImageRepository {

    override fun save(image: GalleryImage): GalleryImage {
        val entity = GalleryImageJpaEntity(
            id = image.id.value,
            salonId = image.salonId.value,
            url = image.url,
            displayOrder = image.displayOrder,
        )
        return jpaRepository.save(entity).toDomain()
    }

    override fun findBySalonId(salonId: SalonId): List<GalleryImage> =
        jpaRepository.findBySalonIdOrderByDisplayOrderAsc(salonId.value).map { it.toDomain() }

    private fun GalleryImageJpaEntity.toDomain(): GalleryImage = GalleryImage.reconstitute(
        id = GalleryImageId(id),
        salonId = SalonId(salonId),
        url = url,
        displayOrder = displayOrder,
        createdAt = createdAt ?: Instant.EPOCH,
    )
}
