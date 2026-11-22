package ai.rojan.backend.infrastructure.persistence.auth

import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.auth.ReviewerAccessGrant
import ai.rojan.backend.domain.auth.ReviewerAccessRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class ReviewerAccessRepositoryAdapter(
    private val jpaRepository: ReviewerAccessGrantSpringDataRepository,
) : ReviewerAccessRepository {

    override fun findByPhoneNumber(phoneNumber: PhoneNumber): ReviewerAccessGrant? =
        jpaRepository.findById(phoneNumber.value).orElse(null)?.toDomain()

    @Transactional
    override fun save(grant: ReviewerAccessGrant): ReviewerAccessGrant {
        val entity = jpaRepository.findById(grant.phoneNumber.value).orElse(null)
            ?.applyMutableFieldsFrom(grant)
            ?: grant.toNewEntity()
        return jpaRepository.saveAndFlush(entity).toDomain()
    }

    private fun ReviewerAccessGrantJpaEntity.applyMutableFieldsFrom(grant: ReviewerAccessGrant): ReviewerAccessGrantJpaEntity = apply {
        codeHash = grant.codeHash
        activatedAt = grant.activatedAt
        expiresAt = grant.expiresAt
        active = grant.active
    }

    private fun ReviewerAccessGrant.toNewEntity() = ReviewerAccessGrantJpaEntity(
        phoneNumber = phoneNumber.value,
        codeHash = codeHash,
        activatedAt = activatedAt,
        expiresAt = expiresAt,
        active = active,
    )

    private fun ReviewerAccessGrantJpaEntity.toDomain() = ReviewerAccessGrant(
        phoneNumber = PhoneNumber(phoneNumber),
        codeHash = codeHash,
        activatedAt = activatedAt,
        expiresAt = expiresAt,
        active = active,
    )
}
