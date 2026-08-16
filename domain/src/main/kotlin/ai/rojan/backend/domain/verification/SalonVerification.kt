package ai.rojan.backend.domain.verification

import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId
import java.time.Instant
import java.util.UUID

@JvmInline
value class SalonVerificationId(val value: UUID) {
    companion object {
        fun new(): SalonVerificationId = SalonVerificationId(UUID.randomUUID())
    }
}

enum class SalonVerificationStatus { PENDING, UNDER_REVIEW, APPROVED, REJECTED, EXPIRED }

private const val MAX_REJECTION_REASON_LENGTH = 1000

/**
 * A single verification submission for a salon - one owner-initiated case
 * reviewed as a unit, not a status flag on the salon itself. Deliberately
 * separate from [ai.rojan.backend.domain.document.SalonDocument] - the
 * documents a case covers are recorded externally (see
 * [SalonVerificationDocumentRepository]), so this aggregate carries no
 * file-shaped state and stays reusable across resubmissions after a
 * rejection.
 *
 * [startReview]/[approve]/[reject] take a `reviewerId` and already enforce
 * the invariants a caller will need (no self-review, must claim before
 * deciding) even though no use case can reach them yet - Platform
 * Authority itself isn't built. Same precedent as
 * [ai.rojan.backend.domain.document.SalonDocument.approve]/`.reject()` in
 * Phase 2: the guard exists the moment the shape does, not bolted on
 * later.
 */
class SalonVerification private constructor(
    val id: SalonVerificationId,
    val salonId: SalonId,
    status: SalonVerificationStatus,
    val submittedBy: UserId,
    val submittedAt: Instant,
    reviewedBy: UserId?,
    reviewedAt: Instant?,
    rejectionReason: String?,
    val createdAt: Instant,
    updatedAt: Instant,
) {
    var status: SalonVerificationStatus = status
        private set

    var reviewedBy: UserId? = reviewedBy
        private set

    var reviewedAt: Instant? = reviewedAt
        private set

    var rejectionReason: String? = rejectionReason
        private set

    var updatedAt: Instant = updatedAt
        private set

    fun startReview(reviewerId: UserId) {
        require(status == SalonVerificationStatus.PENDING) { "Only a pending verification can enter review" }
        require(reviewerId != submittedBy) { "A reviewer cannot review their own submission" }
        status = SalonVerificationStatus.UNDER_REVIEW
        reviewedBy = reviewerId
        reviewedAt = Instant.now()
        touch()
    }

    fun approve(reviewerId: UserId, allDocumentsApproved: Boolean) {
        require(status == SalonVerificationStatus.UNDER_REVIEW) { "Only a verification under review can be approved" }
        require(reviewerId == reviewedBy) { "Only the reviewer who claimed this case may decide it" }
        require(allDocumentsApproved) { "Every linked document must be individually approved before the case can be approved" }
        status = SalonVerificationStatus.APPROVED
        reviewedAt = Instant.now()
        touch()
    }

    fun reject(reviewerId: UserId, reason: String) {
        require(status == SalonVerificationStatus.UNDER_REVIEW) { "Only a verification under review can be rejected" }
        require(reviewerId == reviewedBy) { "Only the reviewer who claimed this case may decide it" }
        require(reason.isNotBlank()) { "A rejection reason is required" }
        require(reason.length <= MAX_REJECTION_REASON_LENGTH) { "Rejection reason must be at most $MAX_REJECTION_REASON_LENGTH characters" }
        status = SalonVerificationStatus.REJECTED
        reviewedAt = Instant.now()
        rejectionReason = reason
        touch()
    }

    /** Scheduled-job territory (no scheduler exists yet) - not request-time, same deferred-infra gap as [ai.rojan.backend.domain.document.SalonDocument.expire]. */
    fun expire() {
        require(status == SalonVerificationStatus.APPROVED) { "Only an approved verification can expire" }
        status = SalonVerificationStatus.EXPIRED
        touch()
    }

    private fun touch() {
        updatedAt = Instant.now()
    }

    companion object {
        fun create(
            salonId: SalonId,
            submittedBy: UserId,
        ): SalonVerification {
            val now = Instant.now()
            return SalonVerification(
                id = SalonVerificationId.new(),
                salonId = salonId,
                status = SalonVerificationStatus.PENDING,
                submittedBy = submittedBy,
                submittedAt = now,
                reviewedBy = null,
                reviewedAt = null,
                rejectionReason = null,
                createdAt = now,
                updatedAt = now,
            )
        }

        fun reconstitute(
            id: SalonVerificationId,
            salonId: SalonId,
            status: SalonVerificationStatus,
            submittedBy: UserId,
            submittedAt: Instant,
            reviewedBy: UserId?,
            reviewedAt: Instant?,
            rejectionReason: String?,
            createdAt: Instant,
            updatedAt: Instant,
        ): SalonVerification = SalonVerification(
            id, salonId, status, submittedBy, submittedAt, reviewedBy, reviewedAt, rejectionReason, createdAt, updatedAt,
        )
    }
}
