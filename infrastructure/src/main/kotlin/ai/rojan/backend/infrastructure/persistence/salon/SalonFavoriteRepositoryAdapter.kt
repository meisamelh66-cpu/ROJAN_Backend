package ai.rojan.backend.infrastructure.persistence.salon

import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.salon.SalonFavorite
import ai.rojan.backend.domain.salon.SalonFavoriteId
import ai.rojan.backend.domain.salon.SalonFavoriteRepository
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId
import org.springframework.data.domain.PageRequest as SpringPageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Repository
class SalonFavoriteRepositoryAdapter(
    private val jpaRepository: SalonFavoriteSpringDataRepository,
) : SalonFavoriteRepository {

    override fun save(favorite: SalonFavorite): SalonFavorite {
        val entity = SalonFavoriteJpaEntity(
            id = favorite.id.value,
            customerId = favorite.customerId.value,
            salonId = favorite.salonId.value,
        )
        return jpaRepository.save(entity).toDomain()
    }

    override fun findByCustomerIdAndSalonId(customerId: UserId, salonId: SalonId): SalonFavorite? =
        jpaRepository.findByCustomerIdAndSalonId(customerId.value, salonId.value)?.toDomain()

    /**
     * Explicit [Transactional] required here: a void-returning derived
     * `deleteBy...` query method is implemented by Spring Data as a
     * find-then-`EntityManager.remove()` per matching row, which needs an
     * active transaction to run in - unlike [save]/the finder methods above,
     * which are already covered by `SimpleJpaRepository`'s own base-class
     * transactionality. Confirmed live: this threw
     * `InvalidDataAccessApiUsageException: No EntityManager with actual
     * transaction available` against the real backend before this
     * annotation was added.
     */
    @Transactional
    override fun deleteByCustomerIdAndSalonId(customerId: UserId, salonId: SalonId) {
        jpaRepository.deleteByCustomerIdAndSalonId(customerId.value, salonId.value)
    }

    override fun findByCustomerId(customerId: UserId, pageRequest: PageRequest): PageResult<SalonFavorite> {
        val pageable = SpringPageRequest.of(pageRequest.page, pageRequest.size, Sort.by(Sort.Direction.DESC, "createdAt"))
        val page = jpaRepository.findByCustomerId(customerId.value, pageable)
        return PageResult(
            content = page.content.map { it.toDomain() },
            page = page.number,
            size = page.size,
            totalElements = page.totalElements,
        )
    }

    private fun SalonFavoriteJpaEntity.toDomain(): SalonFavorite = SalonFavorite.reconstitute(
        id = SalonFavoriteId(id),
        customerId = UserId(customerId),
        salonId = SalonId(salonId),
        createdAt = createdAt ?: Instant.EPOCH,
    )
}
