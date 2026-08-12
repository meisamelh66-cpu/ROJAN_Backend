package ai.rojan.backend.api.publicsalon

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
    val latitude: Double?,
    val longitude: Double?,
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
