package ai.rojan.backend.api.platformauthority

import ai.rojan.backend.application.platformauthority.ManagerSalonAccessType
import ai.rojan.backend.domain.salon.SalonRole
import java.time.Instant
import java.util.UUID

/** [role] is `null` for [ManagerSalonAccessType.OWNER] - ownership is never a [SalonRole] (see that enum's own doc comment). */
data class ManagerSalonAssociationResponse(
    val salonId: UUID,
    val salonName: String,
    val accessType: ManagerSalonAccessType,
    val role: SalonRole?,
)

/**
 * Platform Management API Contract (Manager): deliberately excludes email/avatar/cover (same
 * narrowing [PlatformReviewerResponse] already applies) and, far more importantly, excludes every
 * salon-private field - no [ai.rojan.backend.domain.salon.Permission]s, no catalog/staff/CRM/booking
 * data, nothing beyond which salons this manager can reach and in what capacity
 * ([salonAssociations]). See this contract's own design report for the full field-visibility
 * boundary this type encodes.
 */
data class PlatformManagerResponse(
    val id: UUID,
    val phoneNumber: String?,
    val fullName: String,
    val active: Boolean,
    val createdAt: Instant,
    val salonAssociations: List<ManagerSalonAssociationResponse>,
)

/**
 * Deliberately narrower than [PlatformManagerResponse] - no [salonAssociations] - same
 * "mutation response is the bare account, not the full read shape" precedent
 * [PlatformVerificationResponse] already establishes versus [ai.rojan.backend.api.verification.SalonVerificationResponse].
 * A deactivate/reactivate call already has the caller's own known `managerId` in hand; re-resolving
 * salon associations for the response would be an unused, non-free extra read on a mutation path.
 */
data class PlatformManagerMutationResponse(
    val id: UUID,
    val phoneNumber: String?,
    val fullName: String,
    val active: Boolean,
    val createdAt: Instant,
)
