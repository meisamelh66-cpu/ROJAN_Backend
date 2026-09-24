package ai.rojan.backend.api.verification

import ai.rojan.backend.domain.verification.SalonVerificationStatus
import jakarta.validation.constraints.NotEmpty
import java.time.Instant
import java.util.UUID

data class SubmitVerificationRequest(
    @field:NotEmpty
    val documentIds: List<UUID>,
)

/**
 * [reviewedBy]/[reviewedAt]/[rejectionReason]/[qualityScore]/[decorScore] are `null` until a Platform
 * Authority reviewer acts on this case (Phase 5: `POST /api/v1/platform-authority/salons/{salonId}/verifications/...`)
 * - never writable through this manager-facing controller.
 */
data class SalonVerificationResponse(
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
    val documentIds: List<UUID>,
    val createdAt: Instant,
    val updatedAt: Instant,
)
