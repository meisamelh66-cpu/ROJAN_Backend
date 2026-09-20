package ai.rojan.backend.api.salon

import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.application.salon.GetSalonCompletenessUseCase
import ai.rojan.backend.application.salon.SalonCompletenessResult
import ai.rojan.backend.application.salon.UpdateSalonCompletionProfileCommand
import ai.rojan.backend.application.salon.UpdateSalonCompletionProfileUseCase
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonMembershipId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Salon Completeness + ROJAN Verification (Phase 5): the manager-side profile-completion surface,
 * deliberately separate from [SalonController] (core business fields) - same split
 * [ai.rojan.backend.domain.salon.Salon.updateCompletionProfile]'s own doc comment establishes.
 * Never reports [ai.rojan.backend.domain.salon.Salon.rojanVerified]/verification/hygiene/geo state -
 * that's exposed on [SalonResponse]/[ai.rojan.backend.api.verification.VerificationController]/
 * [ai.rojan.backend.api.document.DocumentController] respectively, never invented here.
 */
@RestController
@RequestMapping("/api/v1/salons/{salonId}/completeness")
@Tag(name = "Salon Completeness")
class SalonCompletenessController(
    private val getSalonCompletenessUseCase: GetSalonCompletenessUseCase,
    private val updateSalonCompletionProfileUseCase: UpdateSalonCompletionProfileUseCase,
    private val currentUserResolver: CurrentUserResolver,
) {

    @GetMapping
    @Operation(summary = "Get a salon's completion profile fields and what's still missing for activation (owner/manager, MANAGE_SALON)")
    fun get(
        @PathVariable salonId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ): SalonCompletenessResponse {
        val callerId = currentUserResolver.resolve(principal)
        return getSalonCompletenessUseCase.execute(SalonId(salonId), callerId).toResponse(salonId)
    }

    @PutMapping
    @Operation(summary = "Update a salon's completion profile fields (owner/manager, MANAGE_SALON)")
    fun update(
        @PathVariable salonId: UUID,
        @Valid @RequestBody request: UpdateSalonCompletionProfileRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): SalonCompletenessResponse {
        val callerId = currentUserResolver.resolve(principal)
        val salon = updateSalonCompletionProfileUseCase.execute(
            UpdateSalonCompletionProfileCommand(
                salonId = SalonId(salonId),
                callerId = callerId,
                activityStartJalaliYear = request.activityStartJalaliYear,
                hasInternalExtensions = request.hasInternalExtensions,
                sellsProducts = request.sellsProducts,
                hasCafe = request.hasCafe,
                hasStaffUniform = request.hasStaffUniform,
                isNeighborhoodSalon = request.isNeighborhoodSalon,
                isCityCenterSalon = request.isCityCenterSalon,
                primaryContactMembershipId = request.primaryContactMembershipId?.let { SalonMembershipId(it) },
            ),
        )
        val missing = getSalonCompletenessUseCase.execute(salon.id, callerId).missingForActivation
        return SalonCompletenessResult(salon, missing).toResponse(salonId)
    }

    private fun SalonCompletenessResult.toResponse(salonId: UUID) = SalonCompletenessResponse(
        salonId = salonId,
        activityStartJalaliYear = salon.activityStartJalaliYear,
        hasInternalExtensions = salon.hasInternalExtensions,
        sellsProducts = salon.sellsProducts,
        hasCafe = salon.hasCafe,
        hasStaffUniform = salon.hasStaffUniform,
        isNeighborhoodSalon = salon.isNeighborhoodSalon,
        isCityCenterSalon = salon.isCityCenterSalon,
        primaryContactMembershipId = salon.primaryContactMembershipId?.value,
        missingForActivation = missingForActivation,
    )
}
