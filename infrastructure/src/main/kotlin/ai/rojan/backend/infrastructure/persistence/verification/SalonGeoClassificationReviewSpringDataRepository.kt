package ai.rojan.backend.infrastructure.persistence.verification

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SalonGeoClassificationReviewSpringDataRepository : JpaRepository<SalonGeoClassificationReviewJpaEntity, UUID> {
    fun findBySalonId(salonId: UUID): List<SalonGeoClassificationReviewJpaEntity>
    fun findByVerificationId(verificationId: UUID): SalonGeoClassificationReviewJpaEntity?
}
