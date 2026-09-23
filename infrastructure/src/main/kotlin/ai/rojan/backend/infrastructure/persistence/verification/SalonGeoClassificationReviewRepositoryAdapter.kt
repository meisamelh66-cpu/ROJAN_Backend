package ai.rojan.backend.infrastructure.persistence.verification

import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.verification.SalonGeoClassificationReview
import ai.rojan.backend.domain.verification.SalonGeoClassificationReviewId
import ai.rojan.backend.domain.verification.SalonGeoClassificationReviewRepository
import ai.rojan.backend.domain.verification.SalonVerificationId
import org.springframework.stereotype.Repository
import java.time.Instant

/** Repository-pattern adapter: implements the domain [SalonGeoClassificationReviewRepository] port on top of Spring Data JPA. */
@Repository
class SalonGeoClassificationReviewRepositoryAdapter(
    private val jpaRepository: SalonGeoClassificationReviewSpringDataRepository,
) : SalonGeoClassificationReviewRepository {

    override fun save(review: SalonGeoClassificationReview): SalonGeoClassificationReview {
        val entity = jpaRepository.findById(review.id.value).orElse(null)
            ?.apply {
                verifiedNeighborhood = review.verifiedNeighborhood
                verifiedCityCenter = review.verifiedCityCenter
            }
            ?: SalonGeoClassificationReviewJpaEntity(
                id = review.id.value,
                salonId = review.salonId.value,
                declaredNeighborhood = review.declaredNeighborhood,
                declaredCityCenter = review.declaredCityCenter,
                verificationId = review.verificationId.value,
                verifiedNeighborhood = review.verifiedNeighborhood,
                verifiedCityCenter = review.verifiedCityCenter,
            )
        return jpaRepository.save(entity).toDomain()
    }

    override fun findBySalonId(salonId: SalonId): List<SalonGeoClassificationReview> =
        jpaRepository.findBySalonId(salonId.value).map { it.toDomain() }

    override fun findByVerificationId(verificationId: SalonVerificationId): SalonGeoClassificationReview? =
        jpaRepository.findByVerificationId(verificationId.value)?.toDomain()

    private fun SalonGeoClassificationReviewJpaEntity.toDomain(): SalonGeoClassificationReview = SalonGeoClassificationReview.reconstitute(
        id = SalonGeoClassificationReviewId(id),
        salonId = SalonId(salonId),
        declaredNeighborhood = declaredNeighborhood,
        declaredCityCenter = declaredCityCenter,
        verificationId = SalonVerificationId(verificationId),
        verifiedNeighborhood = verifiedNeighborhood,
        verifiedCityCenter = verifiedCityCenter,
        createdAt = createdAt ?: Instant.EPOCH,
    )
}
