package ai.rojan.backend.infrastructure.persistence.salon

import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.salon.SalonFollow
import ai.rojan.backend.domain.salon.SalonFollowId
import ai.rojan.backend.domain.salon.SalonFollowRepository
import ai.rojan.backend.domain.salon.SalonFollowStatus
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId
import org.springframework.data.domain.PageRequest as SpringPageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class SalonFollowRepositoryAdapter(
    private val jpaRepository: SalonFollowSpringDataRepository,
) : SalonFollowRepository {

    override fun save(follow: SalonFollow): SalonFollow {
        val entity = jpaRepository.findById(follow.id.value).orElse(null)
            ?.apply { status = follow.status }
            ?: SalonFollowJpaEntity(
                id = follow.id.value,
                customerId = follow.customerId.value,
                salonId = follow.salonId.value,
                status = follow.status,
            )
        return jpaRepository.save(entity).toDomain()
    }

    override fun findByCustomerIdAndSalonId(customerId: UserId, salonId: SalonId): SalonFollow? =
        jpaRepository.findByCustomerIdAndSalonId(customerId.value, salonId.value)?.toDomain()

    override fun findActiveByCustomerId(customerId: UserId, pageRequest: PageRequest): PageResult<SalonFollow> {
        val pageable = SpringPageRequest.of(pageRequest.page, pageRequest.size, Sort.by(Sort.Direction.DESC, "createdAt"))
        val page = jpaRepository.findByCustomerIdAndStatus(customerId.value, SalonFollowStatus.ACTIVE, pageable)
        return PageResult(
            content = page.content.map { it.toDomain() },
            page = page.number,
            size = page.size,
            totalElements = page.totalElements,
        )
    }

    private fun SalonFollowJpaEntity.toDomain(): SalonFollow = SalonFollow.reconstitute(
        id = SalonFollowId(id),
        customerId = UserId(customerId),
        salonId = SalonId(salonId),
        createdAt = createdAt ?: Instant.EPOCH,
        status = status,
    )
}
