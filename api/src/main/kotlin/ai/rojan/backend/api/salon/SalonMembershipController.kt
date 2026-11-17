package ai.rojan.backend.api.salon

import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.application.salon.AssignMembershipCommand
import ai.rojan.backend.application.salon.AssignMembershipUseCase
import ai.rojan.backend.application.salon.LookupStaffCandidateCommand
import ai.rojan.backend.application.salon.LookupStaffCandidateUseCase
import ai.rojan.backend.application.salon.RemoveMembershipCommand
import ai.rojan.backend.application.salon.RemoveMembershipUseCase
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.application.salon.SetMembershipActiveCommand
import ai.rojan.backend.application.salon.SetMembershipActiveUseCase
import ai.rojan.backend.application.salon.StaffCandidate
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonMembership
import ai.rojan.backend.domain.salon.SalonMembershipRepository
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Direct role assignment only (owner-only, [ai.rojan.backend.domain.salon.Permission.MANAGE_MEMBERSHIP])
 * - the assignee must already have a ROJAN account. No invite-token/email
 * flow exists yet (MVP, see the pilot plan's "Later" section); a self-service
 * invite for someone without an account is a UX layer to add on top of this
 * model later, not a redesign of it.
 */
@RestController
@RequestMapping("/api/v1/salons/{salonId}/members")
@Tag(name = "Salon Members")
class SalonMembershipController(
    private val membershipRepository: SalonMembershipRepository,
    private val salonRepository: SalonRepository,
    private val userRepository: UserRepository,
    private val assignMembershipUseCase: AssignMembershipUseCase,
    private val removeMembershipUseCase: RemoveMembershipUseCase,
    private val setMembershipActiveUseCase: SetMembershipActiveUseCase,
    private val lookupStaffCandidateUseCase: LookupStaffCandidateUseCase,
    private val currentUserResolver: CurrentUserResolver,
    private val salonPermissionResolver: SalonPermissionResolver,
) {

    @GetMapping("/lookup")
    @Operation(summary = "Resolve an existing ROJAN account by phone number, to confirm before assigning it a staff role (owner only)")
    fun lookup(
        @PathVariable salonId: UUID,
        @RequestParam phone: String,
        @AuthenticationPrincipal principal: UserDetails,
    ): StaffLookupResponse {
        val callerId = currentUserResolver.resolve(principal)
        val candidate = lookupStaffCandidateUseCase.execute(
            LookupStaffCandidateCommand(SalonId(salonId), callerId, phone),
        )
        return candidate.toResponse()
    }

    @PutMapping("/{userId}")
    @Operation(summary = "Assign (or change) a manager/receptionist role for an existing account at this salon (owner only)")
    fun assign(
        @PathVariable salonId: UUID,
        @PathVariable userId: UUID,
        @Valid @RequestBody request: AssignMembershipRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): SalonMembershipResponse {
        val callerId = currentUserResolver.resolve(principal)
        val membership = assignMembershipUseCase.execute(
            AssignMembershipCommand(SalonId(salonId), callerId, UserId(userId), request.role),
        )
        return membership.toResponse()
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Permanently remove a member's role at this salon (owner only) - true removal, distinct from disabling via PATCH .../status")
    fun remove(
        @PathVariable salonId: UUID,
        @PathVariable userId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ) {
        val callerId = currentUserResolver.resolve(principal)
        removeMembershipUseCase.execute(RemoveMembershipCommand(SalonId(salonId), callerId, UserId(userId)))
    }

    @PatchMapping("/{userId}/status")
    @Operation(summary = "Disable or re-enable a member's access at this salon without deleting the membership (owner only)")
    fun setActive(
        @PathVariable salonId: UUID,
        @PathVariable userId: UUID,
        @Valid @RequestBody request: SetMembershipActiveRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): SalonMembershipResponse {
        val callerId = currentUserResolver.resolve(principal)
        val membership = setMembershipActiveUseCase.execute(
            SetMembershipActiveCommand(SalonId(salonId), callerId, UserId(userId), request.active),
        )
        return membership.toResponse()
    }

    @GetMapping
    @Operation(summary = "List a salon's managers and receptionists (owner only)")
    fun list(@PathVariable salonId: UUID, @AuthenticationPrincipal principal: UserDetails): List<SalonMembershipResponse> {
        val callerId = currentUserResolver.resolve(principal)
        val salon = salonRepository.findById(SalonId(salonId)) ?: throw SalonNotFoundException(salonId.toString())
        salonPermissionResolver.require(salon.id, callerId, Permission.MANAGE_MEMBERSHIP)
        return membershipRepository.findBySalonId(salon.id).map { it.toResponse() }
    }

    private fun SalonMembership.toResponse(): SalonMembershipResponse {
        val user = userRepository.findById(userId)
        return SalonMembershipResponse(
            id = id.value,
            salonId = salonId.value,
            userId = userId.value,
            role = role,
            active = active,
            fullName = user?.fullName ?: "",
            phoneNumber = user?.phoneNumber?.value,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun StaffCandidate.toResponse() = StaffLookupResponse(
        userId = userId.value,
        fullName = fullName,
        phoneNumber = phoneNumber,
    )
}
