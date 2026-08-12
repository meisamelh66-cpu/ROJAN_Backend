package ai.rojan.backend.api.user

import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SalonRole
import java.util.UUID

/**
 * Wire-format response for `GET /api/v1/users/me/salon-access` - see
 * `ai.rojan.backend.application.salon.ResolveMySalonAccessUseCase` for the
 * read path this mirrors. [permissions] on every entry is always the
 * output of [ai.rojan.backend.application.salon.SalonPermissionResolver] -
 * never re-derived here from `role`/ownership, so a client never needs its
 * own copy of that mapping either.
 */
data class SalonAccessResponseDto(
    val ownedSalons: List<OwnedSalonAccessDto>,
    val memberships: List<MembershipAccessDto>,
    val specialistLinks: List<SpecialistAccessDto>,
)

data class OwnedSalonAccessDto(
    val salonId: UUID,
    val salonName: String,
    val active: Boolean,
    val permissions: Set<Permission>,
)

data class MembershipAccessDto(
    val membershipId: UUID,
    val salonId: UUID,
    val salonName: String,
    val active: Boolean,
    val role: SalonRole,
    val permissions: Set<Permission>,
)

data class SpecialistAccessDto(
    val specialistId: UUID,
    val salonId: UUID,
    val salonName: String,
    val active: Boolean,
    val permissions: Set<Permission>,
)
