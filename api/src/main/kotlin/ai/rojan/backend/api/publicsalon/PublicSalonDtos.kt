package ai.rojan.backend.api.publicsalon

import ai.rojan.backend.domain.media.MediaType
import java.math.BigDecimal
import java.util.UUID

/**
 * Deliberately separate from the authenticated [ai.rojan.backend.api.salon.SalonResponse]/
 * [ai.rojan.backend.api.salon.SpecialistResponse] family - those carry
 * internal linkage fields (`ownerId`, `userId`) that don't belong on a
 * response any unauthenticated visitor can request.
 */
data class PublicSalonResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val phone: String,
    val address: String,
    val logoUrl: String?,
    val coverUrl: String?,
    val latitude: Double?,
    val longitude: Double?,
)

/** [storageKey] never exposed - same reasoning as [ai.rojan.backend.api.media.MediaAssetResponse]. */
data class PublicMediaAssetResponse(
    val id: UUID,
    val mediaType: MediaType,
    val url: String,
)

data class PublicServiceCategoryResponse(
    val id: UUID,
    val name: String,
    val description: String?,
)

data class PublicServiceResponse(
    val id: UUID,
    val categoryId: UUID,
    val name: String,
    val description: String?,
    val durationMinutes: Int,
    val price: BigDecimal,
)

data class PublicSpecialistResponse(
    val id: UUID,
    val displayName: String,
    val bio: String?,
    val photoUrl: String?,
)
