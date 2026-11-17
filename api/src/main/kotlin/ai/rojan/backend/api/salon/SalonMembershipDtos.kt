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

/** Staff Access Phase 1: `PATCH /{userId}/status` body - disable/re-enable only, never touches [ai.rojan.backend.domain.salon.SalonMembership.role]. */
data class SetMembershipActiveRequest(
    @field:NotNull
    val active: Boolean,
)

data class SalonMembershipResponse(
    val id: UUID,
    val salonId: UUID,
    val userId: UUID,
    val role: SalonRole,
    val active: Boolean,
    /** Staff Access Phase 1: so a Manager's staff list can show a real name, not a bare UUID - best-effort (empty if the user row is somehow gone), never a second source of truth for identity. */
    val fullName: String,
    val phoneNumber: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Staff Access Phase 1: `GET /lookup?phone=...` response - the minimum identity fields a Manager needs to confirm the match before assigning, never the full user record. */
data class StaffLookupResponse(
    val userId: UUID,
    val fullName: String,
    val phoneNumber: String,
)
