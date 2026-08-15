package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonMembership
import ai.rojan.backend.domain.salon.SalonMembershipRepository
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.Specialist
import ai.rojan.backend.domain.salon.SpecialistRepository
import ai.rojan.backend.domain.user.UserId

data class ResolveMySalonAccessCommand(val callerId: UserId)

data class OwnedSalonAccess(val salon: Salon, val permissions: Set<Permission>)
data class MembershipAccess(val membership: SalonMembership, val salon: Salon, val permissions: Set<Permission>)
data class SpecialistAccess(val specialist: Specialist, val salon: Salon, val permissions: Set<Permission>)

data class MySalonAccess(
    val ownedSalons: List<OwnedSalonAccess>,
    val memberships: List<MembershipAccess>,
    val specialistLinks: List<SpecialistAccess>,
)

/**
 * Identity & Session Architecture, Phase 3: the single read path behind
 * `GET /api/v1/users/me/salon-access` - unifies the three independent
 * sources of salon access [SalonPermissionResolver] already resolves
 * individually (ownership, [SalonMembership], own [Specialist] link) into
 * one "everything this caller can do, across every salon" answer.
 *
 * Deliberately reuses [SalonPermissionResolver.resolve] rather than
 * re-deriving permissions from `salon.ownerId`/`role.permissions()`
 * itself - one permission mapping, one place, same rule this whole phase
 * exists to enforce. A salon a caller no longer has access to (a removed
 * membership, since [SalonMembershipRepository.remove] hard-deletes the
 * row) simply doesn't appear in [findByUserId]'s result - there is no
 * separate "revoked" state to filter out.
 */
class ResolveMySalonAccessUseCase(
    private val salonRepository: SalonRepository,
    private val membershipRepository: SalonMembershipRepository,
    private val specialistRepository: SpecialistRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: ResolveMySalonAccessCommand): MySalonAccess {
        val callerId = command.callerId

        val ownedSalons = salonRepository.findByOwnerId(callerId).map { salon ->
            OwnedSalonAccess(salon, salonPermissionResolver.resolve(salon.id, callerId))
        }

        val memberships = membershipRepository.findByUserId(callerId).mapNotNull { membership ->
            val salon = salonRepository.findById(membership.salonId) ?: return@mapNotNull null
            MembershipAccess(membership, salon, salonPermissionResolver.resolve(salon.id, callerId))
        }

        val specialistLinks = specialistRepository.findByUserId(callerId).mapNotNull { specialist ->
            val salon = salonRepository.findById(specialist.salonId) ?: return@mapNotNull null
            SpecialistAccess(specialist, salon, salonPermissionResolver.resolve(salon.id, callerId))
        }

        return MySalonAccess(ownedSalons, memberships, specialistLinks)
    }
}
