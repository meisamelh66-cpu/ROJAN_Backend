package ai.rojan.backend.infrastructure.persistence.verification

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.util.UUID

/**
 * Persistence model for [ai.rojan.backend.domain.verification.SalonGeoClassificationReview].
 * No `updated_at` column exists (V28), even though [verifiedNeighborhood]/
 * [verifiedCityCenter] are mutated in place after creation
 * ([ai.rojan.backend.domain.verification.SalonGeoClassificationReview.recordVerification]) -
 * matches the real schema exactly; no
 * [org.springframework.data.annotation.LastModifiedDate] field is declared here.
 */
@Entity
@Table(name = "salon_geo_classification_reviews")
@EntityListeners(AuditingEntityListener::class)
class SalonGeoClassificationReviewJpaEntity(
    @Id
    val id: UUID,

    @Column(name = "salon_id", nullable = false)
    val salonId: UUID,

    @Column(name = "declared_neighborhood", nullable = false)
    val declaredNeighborhood: Boolean,

    @Column(name = "declared_city_center", nullable = false)
    val declaredCityCenter: Boolean,

    @Column(name = "verification_id", nullable = false)
    val verificationId: UUID,

    @Column(name = "verified_neighborhood", nullable = true)
    var verifiedNeighborhood: Boolean?,

    @Column(name = "verified_city_center", nullable = true)
    var verifiedCityCenter: Boolean?,
) {
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
}
