package ai.rojan.backend.domain.verification

import ai.rojan.backend.domain.salon.SalonId
import java.time.Instant
import java.util.UUID

@JvmInline
value class SalonGeoClassificationReviewId(val value: UUID) {
    companion object {
        fun new(): SalonGeoClassificationReviewId = SalonGeoClassificationReviewId(UUID.randomUUID())
    }
}

/**
 * Geographic classification is an independent verification item (neighborhood/
 * local vs. city-center), separate from the overall salon verification score.
 * [declaredNeighborhood]/[declaredCityCenter] are a snapshot of what the owner
 * had declared on [ai.rojan.backend.domain.salon.Salon.isNeighborhoodSalon]/
 * [ai.rojan.backend.domain.salon.Salon.isCityCenterSalon] at the time this
 * particular case touched the classification; [verifiedNeighborhood]/
 * [verifiedCityCenter] are the reviewer's own, independent decision - `null`
 * until [recordVerification] is called, and never inferred from the declared
 * values.
 *
 * One row per verification case that actually reviews this classification -
 * not every case is required to. Deliberately never overwrites a prior case's
 * row: a re-review creates a brand-new [SalonGeoClassificationReview] tied to
 * its own [verificationId], so the full declared-vs-verified history across
 * every case is preserved, never destroyed by a later re-review's decision.
 */
class SalonGeoClassificationReview private constructor(
    val id: SalonGeoClassificationReviewId,
    val salonId: SalonId,
    val declaredNeighborhood: Boolean,
    val declaredCityCenter: Boolean,
    val verificationId: SalonVerificationId,
    verifiedNeighborhood: Boolean?,
    verifiedCityCenter: Boolean?,
    val createdAt: Instant,
) {
    var verifiedNeighborhood: Boolean? = verifiedNeighborhood
        private set

    var verifiedCityCenter: Boolean? = verifiedCityCenter
        private set

    /** Records the reviewer's independent decision for this case. A minimal state-recording guard only - same split [ai.rojan.backend.domain.salon.Salon.activate]'s own doc comment already establishes for cross-aggregate decisions. */
    fun recordVerification(verifiedNeighborhood: Boolean, verifiedCityCenter: Boolean) {
        this.verifiedNeighborhood = verifiedNeighborhood
        this.verifiedCityCenter = verifiedCityCenter
    }

    companion object {
        fun create(
            salonId: SalonId,
            declaredNeighborhood: Boolean,
            declaredCityCenter: Boolean,
            verificationId: SalonVerificationId,
        ): SalonGeoClassificationReview = SalonGeoClassificationReview(
            id = SalonGeoClassificationReviewId.new(),
            salonId = salonId,
            declaredNeighborhood = declaredNeighborhood,
            declaredCityCenter = declaredCityCenter,
            verificationId = verificationId,
            verifiedNeighborhood = null,
            verifiedCityCenter = null,
            createdAt = Instant.now(),
        )

        fun reconstitute(
            id: SalonGeoClassificationReviewId,
            salonId: SalonId,
            declaredNeighborhood: Boolean,
            declaredCityCenter: Boolean,
            verificationId: SalonVerificationId,
            verifiedNeighborhood: Boolean?,
            verifiedCityCenter: Boolean?,
            createdAt: Instant,
        ): SalonGeoClassificationReview = SalonGeoClassificationReview(
            id, salonId, declaredNeighborhood, declaredCityCenter, verificationId,
            verifiedNeighborhood, verifiedCityCenter, createdAt,
        )
    }
}
