package ai.rojan.backend.infrastructure.persistence.verification

import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.verification.SalonVerification
import ai.rojan.backend.domain.verification.SalonVerificationId
import ai.rojan.backend.domain.verification.SalonVerificationRepository
import ai.rojan.backend.domain.verification.SalonVerificationStatus
import org.springframework.stereotype.Repository
import java.time.Instant

private val ACTIVE_STATUSES = listOf(SalonVerificationStatus.PENDING, SalonVerificationStatus.UNDER_REVIEW)

/** Repository-pattern adapter: implements the domain [SalonVerificationRepository] port on top of Spring Data JPA. */
@Repository
class SalonVerificationRepositoryAdapter(
    private val jpaRepository: SalonVerificationSpringDataRepository,
) : SalonVerificationRepository {

    override fun save(verification: SalonVerification): SalonVerification {
        val entity = jpaRepository.findById(verification.id.value).orElse(null)
            ?.apply {
                status = verification.status
                reviewedBy = verification.reviewedBy?.value
                reviewedAt = verification.reviewedAt
                rejectionReason = verification.rejectionReason
            }
            ?: SalonVerificationJpaEntity(
                id = verification.id.value,
                salonId = verification.salonId.value,
                status = verification.status,
                submittedBy = verification.submittedBy.value,
                submittedAt = verification.submittedAt,
                reviewedBy = verification.reviewedBy?.value,
                reviewedAt = verification.reviewedAt,
                rejectionReason = verification.rejectionReason,
            )
        return jpaRepository.save(entity).toDomain()
    }

    override fun findByIdAndSalonId(id: SalonVerificationId, salonId: SalonId): SalonVerification? =
        jpaRepository.findByIdAndSalonId(id.value, salonId.value)?.toDomain()

    override fun findCurrentBySalonId(salonId: SalonId): SalonVerification? =
        jpaRepository.findFirstBySalonIdAndStatusIn(salonId.value, ACTIVE_STATUSES)?.toDomain()

    override fun findHistoryBySalonId(salonId: SalonId): List<SalonVerification> =
        jpaRepository.findBySalonIdOrderByCreatedAtDesc(salonId.value).map { it.toDomain() }

    private fun SalonVerificationJpaEntity.toDomain(): SalonVerification = SalonVerification.reconstitute(
        id = SalonVerificationId(id),
        salonId = SalonId(salonId),
        status = status,
        submittedBy = UserId(submittedBy),
        submittedAt = submittedAt,
        reviewedBy = reviewedBy?.let { UserId(it) },
        reviewedAt = reviewedAt,
        rejectionReason = rejectionReason,
        createdAt = createdAt ?: Instant.EPOCH,
        updatedAt = updatedAt ?: Instant.EPOCH,
    )
}
