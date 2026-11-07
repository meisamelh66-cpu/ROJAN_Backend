package ai.rojan.backend.api.salon

import ai.rojan.backend.domain.salon.GalleryImage
import ai.rojan.backend.domain.salon.GalleryImageRepository
import ai.rojan.backend.domain.salon.SalonId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * D6 (Build now): Customer's read access to a salon's gallery. Any authenticated user, same
 * authorization shape as [SpecialistController.list] - no owner-only gate, since nothing here is
 * sensitive. No upload/reorder/delete endpoint exists yet - rows are seeded directly
 * ([GalleryImageRepository.save]) until salon-side gallery management is built separately.
 */
@RestController
@RequestMapping("/api/v1/salons/{salonId}/gallery")
@Tag(name = "Gallery")
class GalleryController(
    private val galleryImageRepository: GalleryImageRepository,
) {

    @GetMapping
    @Operation(summary = "List a salon's gallery images, ordered for display")
    fun list(@PathVariable salonId: UUID): List<GalleryImageResponse> =
        galleryImageRepository.findBySalonId(SalonId(salonId)).map { it.toResponse() }

    private fun GalleryImage.toResponse() = GalleryImageResponse(
        id = id.value,
        salonId = salonId.value,
        url = url,
        displayOrder = displayOrder,
    )
}
