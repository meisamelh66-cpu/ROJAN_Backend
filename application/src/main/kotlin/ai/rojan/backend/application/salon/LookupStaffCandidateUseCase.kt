package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.common.UserNotFoundException
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository

data class LookupStaffCandidateCommand(val salonId: SalonId, val callerId: UserId, val phoneNumber: String)

/** Minimum identity fields a Manager needs to confirm "is this the right person?" before assigning - never the full [ai.rojan.backend.domain.user.User] record. */
data class StaffCandidate(val userId: UserId, val fullName: String, val phoneNumber: String)

/**
 * Staff Access Phase 1 (Phone -> OTP -> User -> SalonMembership -> Role -> Permissions ->
 * Active/Disabled): resolves an existing ROJAN account by phone number so an owner/authorized
 * manager can grant it salon access, without creating one and without exposing anything beyond
 * the minimum identity fields needed to confirm the match.
 *
 * Deliberately **not** [LookupUserForCustomerLinkUseCase] reused: that use case is scoped to an
 * already-on-file [ai.rojan.backend.domain.customer.Customer]'s own phone number under
 * [Permission.MANAGE_CRM] (a CRM-linking concern); this one accepts an arbitrary caller-supplied
 * phone number under [Permission.MANAGE_MEMBERSHIP] (a staff-access concern) - different inputs,
 * different permission boundary, so collapsing them into one use case would blur both.
 *
 * Tenant-scoped by construction: [command.salonId] must be a salon the caller already has
 * [Permission.MANAGE_MEMBERSHIP] on - there is no way to invoke this against a salon the caller
 * doesn't manage, and the response never discloses anything about any *other* salon the matched
 * user belongs to. An inactive account is treated identically to no match
 * ([UserNotFoundException]) - mirroring [LookupUserForCustomerLinkUseCase]'s own rule - so this
 * can never be used to enumerate which phone numbers have (deactivated) ROJAN accounts.
 *
 * Never persists anything - pure read.
 */
class LookupStaffCandidateUseCase(
    private val salonRepository: SalonRepository,
    private val userRepository: UserRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: LookupStaffCandidateCommand): StaffCandidate {
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        salonPermissionResolver.require(salon.id, command.callerId, Permission.MANAGE_MEMBERSHIP)

        // PhoneNumber's own init{} throws IllegalArgumentException (-> HTTP 400) for a malformed value.
        val phoneNumber = PhoneNumber(command.phoneNumber)

        val user = userRepository.findByPhoneNumber(phoneNumber)
            ?.takeIf { it.active }
            ?: throw UserNotFoundException(phoneNumber.value)

        return StaffCandidate(userId = user.id, fullName = user.fullName, phoneNumber = phoneNumber.value)
    }
}
