package ai.rojan.backend.domain.salon

import java.time.Instant
import java.util.UUID

@JvmInline
value class GalleryImageId(val value: UUID) {
    companion object {
        fun new(): GalleryImageId = GalleryImageId(UUID.randomUUID())
    }
}

/**
 * One photo in a [Salon]'s customer-facing gallery. D6 (Build now): Customer's read access only -
 * no owner-facing upload/reorder/delete endpoint exists yet, so rows must be seeded directly (e.g.
 * via [GalleryImageRepository.save]) until salon-side gallery management is built as its own,
 * separate piece of work.
 */
class GalleryImage private constructor(
    val id: GalleryImageId,
    val salonId: SalonId,
    val url: String,
    val displayOrder: Int,
    val createdAt: Instant,
) {
    companion object {
        fun create(salonId: SalonId, url: String, displayOrder: Int): GalleryImage {
            require(url.isNotBlank()) { "Gallery image url must not be blank" }
            return GalleryImage(
                id = GalleryImageId.new(),
                salonId = salonId,
                url = url.trim(),
                displayOrder = displayOrder,
                createdAt = Instant.now(),
            )
        }

        fun reconstitute(
            id: GalleryImageId,
            salonId: SalonId,
            url: String,
            displayOrder: Int,
            createdAt: Instant,
        ): GalleryImage = GalleryImage(id, salonId, url, displayOrder, createdAt)
    }
}

/** A salon's customer-facing gallery, ordered for display. */
interface GalleryImageRepository {
    fun save(image: GalleryImage): GalleryImage
    fun findBySalonId(salonId: SalonId): List<GalleryImage>
}
