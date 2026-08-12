package ai.rojan.backend.api.invite

import ai.rojan.backend.domain.salon.SalonRole
import java.util.UUID

/** Deliberately minimal - the confirmation-screen shape, never leaks anything about the invite beyond what's needed to ask "join this salon in this role?". */
data class InviteDetailsResponse(
    val salonName: String,
    val role: SalonRole,
)

data class SalonInviteAcceptedResponse(
    val salonId: UUID,
    val role: SalonRole,
)
