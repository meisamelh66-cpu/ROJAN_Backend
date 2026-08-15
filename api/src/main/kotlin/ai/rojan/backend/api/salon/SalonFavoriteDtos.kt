package ai.rojan.backend.api.salon

import java.time.Instant
import java.util.UUID

data class SalonFavoriteResponse(
    val id: UUID,
    val salonId: UUID,
    val createdAt: Instant,
)
