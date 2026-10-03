package ai.rojan.backend.api.platformauthority

import ai.rojan.backend.domain.salon.SalonOnboardingStatus
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/**
 * [ownerName] is the one field [ai.rojan.backend.api.salon.SalonResponse] does not carry - resolved
 * server-side (`SalonRepository.findAllForPlatform`) so the Website's admin directory never needs a
 * per-row owner lookup (`PlatformSalonRow` in `lib/types/platform-salon.ts`). Every other field is
 * the same shape as [ai.rojan.backend.api.salon.SalonResponse] - a separate type rather than
 * reusing that one directly, so the public/owner-facing contract never has to carry an
 * admin-only field.
 */
data class PlatformSalonResponse(
    val id: UUID,
    val ownerId: UUID,
    val ownerName: String?,
    val name: String,
    val description: String?,
    val phone: String,
    val email: String?,
    val address: String,
    val slug: String,
    val onboardingStatus: SalonOnboardingStatus,
    val logoMediaId: UUID?,
    val coverMediaId: UUID?,
    val logoUrl: String?,
    val coverImageUrl: String?,
    val latitude: Double?,
    val longitude: Double?,
    val city: String?,
    val active: Boolean,
    val rojanVerified: Boolean,
    val rojanVerifiedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class SuspendPlatformSalonRequest(
    @field:NotBlank
    @field:Size(max = 500)
    @field:Schema(example = "سالن تستی/تکراری بود", description = "Shown to no one but platform operators - recorded as this action's AuditEvent.metadata")
    val reason: String,
)

/**
 * Admin Salon Edit - mirrors [ai.rojan.backend.api.salon.UpdateSalonRequest] plus
 * [ai.rojan.backend.api.salon.UpdateSalonCompletionProfileRequest]'s fields in one combined body -
 * see [ai.rojan.backend.application.platformauthority.UpdatePlatformSalonCommand]'s own doc comment
 * for why this is one endpoint instead of the owner API's three. Every field here mirrors its
 * owner-facing counterpart's own validation exactly - no stricter, no looser.
 */
data class UpdatePlatformSalonRequest(
    @field:NotBlank
    @field:Size(max = 255)
    val name: String,

    @field:Size(max = 2000)
    val description: String?,

    @field:NotBlank
    @field:Size(max = 32)
    val phone: String,

    @field:Email
    @field:Size(max = 255)
    val email: String?,

    @field:NotBlank
    @field:Size(max = 500)
    val address: String,

    @field:DecimalMin("-90.0")
    @field:DecimalMax("90.0")
    val latitude: Double? = null,

    @field:DecimalMin("-180.0")
    @field:DecimalMax("180.0")
    val longitude: Double? = null,

    @field:Size(max = 120)
    val city: String? = null,

    val activityStartJalaliYear: Int? = null,
    val hasInternalExtensions: Boolean = false,
    val sellsProducts: Boolean? = null,
    val hasCafe: Boolean? = null,
    val hasStaffUniform: Boolean? = null,
    val isNeighborhoodSalon: Boolean? = null,
    val isCityCenterSalon: Boolean? = null,
)
