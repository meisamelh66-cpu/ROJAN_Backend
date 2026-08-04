package ai.rojan.backend.infrastructure.persistence.customer

import ai.rojan.backend.domain.customer.CustomerActivity
import ai.rojan.backend.domain.customer.CustomerActivityId
import ai.rojan.backend.domain.customer.CustomerActivityRepository
import ai.rojan.backend.domain.customer.CustomerId
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class CustomerActivityRepositoryAdapter(
    private val jpaRepository: CustomerActivitySpringDataRepository,
) : CustomerActivityRepository {

    override fun save(activity: CustomerActivity): CustomerActivity {
        val entity = CustomerActivityJpaEntity(
            id = activity.id.value,
            customerId = activity.customerId.value,
            type = activity.type,
            description = activity.description,
        )
        return jpaRepository.save(entity).toDomain()
    }

    override fun findByCustomerId(customerId: CustomerId): List<CustomerActivity> =
        jpaRepository.findByCustomerId(customerId.value).map { it.toDomain() }

    private fun CustomerActivityJpaEntity.toDomain(): CustomerActivity = CustomerActivity(
        id = CustomerActivityId(id),
        customerId = CustomerId(customerId),
        type = type,
        description = description,
        occurredAt = occurredAt ?: Instant.EPOCH,
    )
}
