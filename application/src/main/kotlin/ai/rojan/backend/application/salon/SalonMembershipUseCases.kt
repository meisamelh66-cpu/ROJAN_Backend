package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.InvalidMembershipAssignmentException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.common.UserNotFoundException
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonMembership
import ai.rojan.backend.domain.salon.SalonMembershipRepository
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.SalonRole
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository

data class AssignMembershipCommand(val salonId: SalonId, val callerId: UserId, val targetUserId: UserId, val role: SalonRole)

/**
 * Owner-only ([Permission.MANAGE_MEMBERSHIP] - deliberately not granted to
 * [SalonRole.MANAGER], see that enum's doc comment on the privilege-escalation
 * chain this closes). [targetUserId] must already have a ROJAN account - no
 * invite-token flow exists yet (MVP, see the pilot plan's §5 "Later").
 */
class AssignMembershipUseCase(
    private val salonRepository: SalonRepository,
    private val userRepository: UserRepository,
    private val membershipRepository: SalonMembershipRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: AssignMembershipCommand): SalonMembership {
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        salonPermissionResolver.require(salon.id, command.callerId, Permission.MANAGE_MEMBERSHIP)

        if (command.targetUserId == salon.ownerId) {
            throw InvalidMembershipAssignmentException("Cannot assign a membership role to the salon's own owner: ${salon.ownerId.value}")
        }

        userRepository.findById(command.targetUserId)
            ?: throw UserNotFoundException(command.targetUserId.value.toString())

        return membershipRepository.assign(salon.id, command.targetUserId, command.role)
    }
}

data class RemoveMembershipCommand(val salonId: SalonId, val callerId: UserId, val targetUserId: UserId)

class RemoveMembershipUseCase(
    private val salonRepository: SalonRepository,
    private val membershipRepository: SalonMembershipRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: RemoveMembershipCommand) {
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        salonPermissionResolver.require(salon.id, command.callerId, Permission.MANAGE_MEMBERSHIP)

        membershipRepository.remove(salon.id, command.targetUserId)
    }
}
