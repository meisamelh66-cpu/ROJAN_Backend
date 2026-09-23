package ai.rojan.backend.domain.verification

import ai.rojan.backend.domain.salon.SalonId

/**
 * Output port for [SalonGeoClassificationReview] persistence. No update/delete
 * operation is exposed - a review row's [SalonGeoClassificationReview.recordVerification]
 * mutation is persisted through [save] (find-or-create, matching every other
 * adapter in this codebase), and rows are never destructively replaced - a new
 * case creates a new row, preserving the full declared-vs-verified history.
 */
interface SalonGeoClassificationReviewRepository {
    fun save(review: SalonGeoClassificationReview): SalonGeoClassificationReview

    /** Every geo-classification review ever recorded for a salon, across every verification case. */
    fun findBySalonId(salonId: SalonId): List<SalonGeoClassificationReview>

    /** The one review (if any) tied to a specific verification case - not every case reviews geo classification. */
    fun findByVerificationId(verificationId: SalonVerificationId): SalonGeoClassificationReview?
}
