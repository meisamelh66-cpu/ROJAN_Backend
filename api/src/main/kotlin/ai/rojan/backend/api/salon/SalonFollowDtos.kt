package ai.rojan.backend.api.salon

import ai.rojan.backend.domain.salon.SalonFollowStatus
import java.time.Instant
import java.util.UUID

data class SalonFollowResponse(
    val id: UUID,
    val salonId: UUID,
    val status: SalonFollowStatus,
    val createdAt: Instant,
)
