package ai.rojan.backend.api.salon

import java.util.UUID

/**
 * Salon Completeness profile fields only - mirrors [ai.rojan.backend.application.salon.UpdateSalonCompletionProfileCommand]
 * field for field. [hasInternalExtensions] defaults `false` for the same reason the command itself does
 * (see its own doc comment) - a caller that hasn't touched extensions yet doesn't need to know the
 * salon's exact current value in advance.
 */
data class UpdateSalonCompletionProfileRequest(
    val activityStartJalaliYear: Int? = null,
    val hasInternalExtensions: Boolean = false,
    val sellsProducts: Boolean? = null,
    val hasCafe: Boolean? = null,
    val hasStaffUniform: Boolean? = null,
    val isNeighborhoodSalon: Boolean? = null,
    val isCityCenterSalon: Boolean? = null,
    val primaryContactMembershipId: UUID? = null,
)

/**
 * [missingForActivation] is exactly [ai.rojan.backend.application.salon.GetSalonCompletenessUseCase]'s
 * own list - the same requirements [ai.rojan.backend.application.salon.ActivateSalonUseCase] enforces,
 * never a separately invented completeness percentage. Every completion field's real persisted value
 * reaches the client unmodified, including the `null` vs `false` distinction on the optional-but-answered
 * ones.
 */
data class SalonCompletenessResponse(
    val salonId: UUID,
    val activityStartJalaliYear: Int?,
    val hasInternalExtensions: Boolean,
    val sellsProducts: Boolean?,
    val hasCafe: Boolean?,
    val hasStaffUniform: Boolean?,
    val isNeighborhoodSalon: Boolean?,
    val isCityCenterSalon: Boolean?,
    val primaryContactMembershipId: UUID?,
    val missingForActivation: List<String>,
)
