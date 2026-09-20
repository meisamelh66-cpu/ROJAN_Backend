package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.InvalidMembershipAssignmentException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonMembershipId
import ai.rojan.backend.domain.salon.SalonMembershipRepository
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId

/**
 * Salon Completeness profile fields only - deliberately a separate command
 * from [UpdateSalonCommand], same reasoning [Salon.updateCompletionProfile]'s
 * own doc comment gives: an owner answering "do you have a cafe?" should
 * never need to resubmit name/phone/address too. [hasInternalExtensions]
 * defaults `false` to match [Salon]'s own default for a brand-new salon, but
 * every caller is expected to pass the salon's real current value (a
 * completion-profile edit is a full replace of this field group, not a
 * partial patch) - optional here only so a caller that hasn't touched
 * extensions yet doesn't need to know the exact current value in advance.
 */
data class UpdateSalonCompletionProfileCommand(
    val salonId: SalonId,
    val callerId: UserId,
    val activityStartJalaliYear: Int?,
    val hasInternalExtensions: Boolean = false,
    val sellsProducts: Boolean?,
    val hasCafe: Boolean?,
    val hasStaffUniform: Boolean?,
    val isNeighborhoodSalon: Boolean?,
    val isCityCenterSalon: Boolean?,
    val primaryContactMembershipId: SalonMembershipId?,
)

class UpdateSalonCompletionProfileUseCase(
    private val salonRepository: SalonRepository,
    private val membershipRepository: SalonMembershipRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: UpdateSalonCompletionProfileCommand): Salon {
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        salonPermissionResolver.require(salon.id, command.callerId, Permission.MANAGE_SALON)

        command.primaryContactMembershipId?.let { requireBelongsToSalon(it, salon.id, membershipRepository) }

        salon.updateCompletionProfile(
            activityStartJalaliYear = command.activityStartJalaliYear,
            hasInternalExtensions = command.hasInternalExtensions,
            sellsProducts = command.sellsProducts,
            hasCafe = command.hasCafe,
            hasStaffUniform = command.hasStaffUniform,
            isNeighborhoodSalon = command.isNeighborhoodSalon,
            isCityCenterSalon = command.isCityCenterSalon,
            primaryContactMembershipId = command.primaryContactMembershipId,
        )
        return salonRepository.save(salon)
    }
}

/**
 * Shared by [UpdateSalonCompletionProfileUseCase] - the one validation the
 * approved design explicitly requires: a designated primary-contact
 * membership must belong to the salon it's being set on. Read-only - never
 * creates a membership, never changes its role/ownership, reuses the
 * already-existing [SalonMembershipRepository.findBySalonId] rather than
 * adding a new repository method for this.
 */
internal fun requireBelongsToSalon(
    membershipId: SalonMembershipId,
    salonId: SalonId,
    membershipRepository: SalonMembershipRepository,
) {
    val belongs = membershipRepository.findBySalonId(salonId).any { it.id == membershipId }
    if (!belongs) {
        throw InvalidMembershipAssignmentException(
            "Membership ${membershipId.value} does not belong to salon ${salonId.value} and cannot be set as its primary contact",
        )
    }
}
