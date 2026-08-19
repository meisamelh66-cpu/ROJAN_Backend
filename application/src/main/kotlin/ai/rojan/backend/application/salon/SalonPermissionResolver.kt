package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonMembershipRepository
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.Specialist
import ai.rojan.backend.domain.salon.SpecialistRepository
import ai.rojan.backend.domain.user.UserId

/**
 * The single place [Permission]s are resolved for a caller at a salon -
 * every use case that used to write its own `salon.ownerId != callerId`
 * check calls [require] (or [requireCanManageSpecialist] for a
 * per-specialist action) instead, so the three sources of authority (owner,
 * [ai.rojan.backend.domain.salon.SalonMembership], own
 * [ai.rojan.backend.domain.salon.Specialist] link) can never drift apart
 * between call sites.
 */
class SalonPermissionResolver(
    private val salonRepository: SalonRepository,
    private val membershipRepository: SalonMembershipRepository,
    private val specialistRepository: SpecialistRepository,
) {
    /** Owner: every permission. Member: their [ai.rojan.backend.domain.salon.SalonRole]'s set. Own specialist link: [Permission.MANAGE_SCHEDULE_OWN] only. Otherwise: none. */
    fun resolve(salonId: SalonId, callerId: UserId): Set<Permission> {
        val salon = salonRepository.findById(salonId) ?: return emptySet()
        if (salon.ownerId == callerId) return Permission.entries.toSet()

        membershipRepository.findBySalonIdAndUserId(salonId, callerId)?.let { return it.role.permissions() }

        specialistRepository.findBySalonIdAndUserId(salonId, callerId)?.let { return setOf(Permission.MANAGE_SCHEDULE_OWN) }

        return emptySet()
    }

    fun require(salonId: SalonId, callerId: UserId, permission: Permission) {
        if (permission !in resolve(salonId, callerId)) {
            throw SalonAccessDeniedException(salonId.value.toString())
        }
    }

    /** Like [require], but passes if the caller holds at least one of [anyOfPermissions] - e.g. a narrow Reception permission or the broader CRM one it stands in for. */
    fun requireAny(salonId: SalonId, callerId: UserId, vararg anyOfPermissions: Permission) {
        val resolved = resolve(salonId, callerId)
        if (anyOfPermissions.none { it in resolved }) {
            throw SalonAccessDeniedException(salonId.value.toString())
        }
    }

    /**
     * For an action on one specific [specialist]: true if the caller holds
     * [allPermission] at the salon (e.g. [Permission.MANAGE_SCHEDULE_ALL] for
     * schedule endpoints, [Permission.MANAGE_STAFF] for eligibility
     * assignment), OR the caller *is* that specialist ([Permission.MANAGE_SCHEDULE_OWN],
     * strictly scoped to their own record - never anyone else's).
     */
    fun canManageSpecialist(specialist: Specialist, callerId: UserId, allPermission: Permission): Boolean {
        val permissions = resolve(specialist.salonId, callerId)
        if (allPermission in permissions) return true
        return Permission.MANAGE_SCHEDULE_OWN in permissions && specialist.userId == callerId
    }

    fun requireCanManageSpecialist(specialist: Specialist, callerId: UserId, allPermission: Permission) {
        if (!canManageSpecialist(specialist, callerId, allPermission)) {
            throw SalonAccessDeniedException(specialist.salonId.value.toString())
        }
    }
}
