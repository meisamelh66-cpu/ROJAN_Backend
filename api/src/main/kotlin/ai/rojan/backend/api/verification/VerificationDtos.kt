package ai.rojan.backend.api.verification

import ai.rojan.backend.domain.verification.SalonVerificationStatus
import jakarta.validation.constraints.NotEmpty
import java.time.Instant
import java.util.UUID

data class SubmitVerificationRequest(
    @field:NotEmpty
    val documentIds: List<UUID>,
)

/** [reviewedBy]/[reviewedAt]/[rejectionReason] stay null for every case reachable this phase - review/approve/reject aren't wired to any use case or endpoint yet. */
data class SalonVerificationResponse(
    val id: UUID,
    val salonId: UUID,
    val status: SalonVerificationStatus,
    val submittedBy: UUID,
    val submittedAt: Instant,
    val reviewedBy: UUID?,
    val reviewedAt: Instant?,
    val rejectionReason: String?,
    val documentIds: List<UUID>,
    val createdAt: Instant,
    val updatedAt: Instant,
)
