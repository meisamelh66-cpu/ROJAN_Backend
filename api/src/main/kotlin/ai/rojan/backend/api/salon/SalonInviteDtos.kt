package ai.rojan.backend.api.salon

import ai.rojan.backend.domain.salon.SalonInviteStatus
import ai.rojan.backend.domain.salon.SalonRole
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.util.UUID

data class CreateSalonInviteRequest(
    @field:NotNull
    val role: SalonRole,
)

data class SalonInviteResponse(
    val id: UUID,
    val salonId: UUID,
    val role: SalonRole,
    val token: String,
    val status: SalonInviteStatus,
    val expiresAt: Instant,
    val createdBy: UUID,
    val acceptedBy: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
