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
    val coverImageUrl: String?,
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

/**
 * Public Salon Marketplace (Phase 1): one marketplace card's worth of data - deliberately
 * narrower than [PublicSalonResponse] (no `phone`/`address`/`description`, which stay behind the
 * salon's own already-public single-salon page, and never `ownerId` or any other internal field).
 * [slug] is the real link target - the marketplace links out to the salon's own tenant site, never
 * embeds its content.
 */
data class PublicSalonListResponse(
    val id: UUID,
    val slug: String,
    val name: String,
    val logoUrl: String?,
    val coverUrl: String?,
    val city: String?,
    /**
     * LBS Architecture (Phase 5): the real, computed distance in kilometers from the caller's
     * supplied `lat`/`lng`, present only on a "nearby" query - `null` for every existing/default call
     * shape (additive, backward compatible: an old client that never sent `lat`/`lng` sees an
     * identical response shape to before, just with one new field it can safely ignore).
     */
    val distanceKm: Double? = null,
)
