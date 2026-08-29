package ai.rojan.backend.api.salon

import ai.rojan.backend.domain.salon.IdentitySlot
import ai.rojan.backend.domain.salon.SalonOnboardingStatus
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class CreateSalonRequest(
    @field:NotBlank
    @field:Size(max = 255)
    @field:Schema(example = "Glow Salon")
    val name: String,

    @field:Size(max = 2000)
    @field:Schema(example = "Full-service hair and beauty salon in downtown.")
    val description: String?,

    @field:NotBlank
    @field:Size(max = 32)
    @field:Schema(example = "+1 555 0100")
    val phone: String,

    @field:Email
    @field:Size(max = 255)
    @field:Schema(example = "hello@glowsalon.example")
    val email: String?,

    @field:NotBlank
    @field:Size(max = 500)
    @field:Schema(example = "1 Main St, Springfield")
    val address: String,
)

/** [latitude]/[longitude]/[city] follow "null means leave unchanged" merge semantics - see `UpdateSalonCommand`'s own doc comment. Logo/cover are set exclusively through `PUT /salons/{salonId}/identity-media`, by `MediaAssetId`, never a URL here - see [SalonResponse.logoMediaId]. */
data class UpdateSalonRequest(
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
    @field:Schema(example = "Tehran", description = "Public Salon Marketplace (Phase 1): a plain city name - no fixed city list is enforced")
    val city: String? = null,
)

/**
 * [logoUrl]/[coverImageUrl] are resolved server-side from [logoMediaId]/[coverMediaId]
 * at read time (via `MediaStoragePort`) - `Salon` itself stores only the
 * ids, never a URL. [logoMediaId]/[coverMediaId] are exposed for clients
 * that want the raw reference rather than parsing a URL.
 */
data class SalonResponse(
    val id: UUID,
    val ownerId: UUID,
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
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ChangeSalonSlugRequest(
    @field:NotBlank
    @field:Size(max = 80)
    @field:Schema(example = "glow-salon", description = "Normalized (lowercased, non-alphanumeric collapsed to '-') before uniqueness is checked")
    val slug: String,
)

/** [mediaId] `null` clears the slot. A non-null [mediaId] must reference a `MediaAsset` belonging to this salon whose `mediaType` matches [slot] - enforced by `AssignIdentityMediaUseCase`, not here. */
data class AssignIdentityMediaRequest(
    @field:NotNull
    val slot: IdentitySlot,

    val mediaId: UUID? = null,
)
