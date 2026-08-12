package ai.rojan.backend.api.salon

import ai.rojan.backend.domain.salon.SalonRole
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.util.UUID

data class AssignMembershipRequest(
    @field:NotNull
    @field:Schema(example = "MANAGER", description = "MANAGER (operational control - catalog, staff, schedules, CRM, bookings) or RECEPTIONIST (booking operations only)")
    val role: SalonRole,
)

data class SalonMembershipResponse(
    val id: UUID,
    val salonId: UUID,
    val userId: UUID,
    val role: SalonRole,
    val createdAt: Instant,
    val updatedAt: Instant,
)
