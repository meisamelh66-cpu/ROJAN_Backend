package ai.rojan.backend.api.salon

import java.util.UUID

data class GalleryImageResponse(
    val id: UUID,
    val salonId: UUID,
    val url: String,
    val displayOrder: Int,
)
