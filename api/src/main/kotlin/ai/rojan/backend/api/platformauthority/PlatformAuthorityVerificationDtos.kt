package ai.rojan.backend.api.platformauthority

import ai.rojan.backend.domain.verification.SalonVerificationStatus
import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.util.UUID

/**
 * Deliberately separate from [ai.rojan.backend.api.verification.SalonVerificationResponse] - the
 * platform-authority use cases this controller wires
 * ([ai.rojan.backend.application.verification.InitiateRojanReviewUseCase] et al.) return a bare
 * [ai.rojan.backend.domain.verification.SalonVerification], never a
 * [ai.rojan.backend.application.verification.VerificationWithDocuments], so there is no
 * `documentIds` list to expose here without an extra, business-logic-shaped repository call in the
 * controller - a reviewer already has that via `GET /api/v1/salons/{salonId}/documents`.
 */
data class PlatformVerificationResponse(
    val id: UUID,
    val salonId: UUID,
    val status: SalonVerificationStatus,
    val submittedBy: UUID,
    val submittedAt: Instant,
    val reviewedBy: UUID?,
    val reviewedAt: Instant?,
    val rejectionReason: String?,
    val qualityScore: Int?,
    val decorScore: Int?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class PagedVerificationResponse(
    val content: List<PlatformVerificationResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

/**
 * [qualityScore] must be `1..5` when supplied - enforced by [ai.rojan.backend.domain.verification.SalonVerification.approve]'s
 * own domain guard, not re-validated here. [verifiedNeighborhood]/[verifiedCityCenter] are optional and
 * independent of the rest of this request - see [ai.rojan.backend.application.verification.ApproveSalonVerificationCommand]'s
 * own doc comment; this is the only place geographic verification is exposed (Phase 5 §09) - no
 * standalone geo-review endpoint exists because no standalone application use case does.
 */
data class ApproveSalonVerificationRequest(
    val qualityScore: Int? = null,
    val decorScore: Int? = null,
    val verifiedNeighborhood: Boolean? = null,
    val verifiedCityCenter: Boolean? = null,
)

data class RejectSalonVerificationRequest(
    @field:NotBlank
    val reason: String,
)

/**
 * API contract-completion phase: read-only projection of
 * [ai.rojan.backend.domain.verification.SalonGeoClassificationReview]. Every field is `null` when
 * no geo classification review was ever recorded for this case - a legitimate, honest absence (see
 * [ai.rojan.backend.application.verification.GetGeoClassificationForPlatformUseCase]'s own doc
 * comment), not an error. [declaredNeighborhood]/[declaredCityCenter] are the owner's snapshot at
 * review time; [verifiedNeighborhood]/[verifiedCityCenter] are the reviewer's independent decision -
 * never merged into one pair of fields, so the distinction between declaration and verification is
 * never lost.
 */
data class GeoClassificationResponse(
    val declaredNeighborhood: Boolean?,
    val declaredCityCenter: Boolean?,
    val verifiedNeighborhood: Boolean?,
    val verifiedCityCenter: Boolean?,
)
