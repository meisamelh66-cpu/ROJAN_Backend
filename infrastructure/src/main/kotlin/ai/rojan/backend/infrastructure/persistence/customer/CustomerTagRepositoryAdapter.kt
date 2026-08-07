package ai.rojan.backend.infrastructure.persistence.customer

import ai.rojan.backend.domain.customer.CustomerId
import ai.rojan.backend.domain.customer.CustomerTag
import ai.rojan.backend.domain.customer.CustomerTagId
import ai.rojan.backend.domain.customer.CustomerTagRepository
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class CustomerTagRepositoryAdapter(
    private val jpaRepository: CustomerTagSpringDataRepository,
) : CustomerTagRepository {

    override fun save(tag: CustomerTag): CustomerTag {
        val entity = CustomerTagJpaEntity(id = tag.id.value, customerId = tag.customerId.value, label = tag.label)
        return jpaRepository.save(entity).toDomain()
    }

    override fun findById(id: CustomerTagId): CustomerTag? =
        jpaRepository.findById(id.value).orElse(null)?.toDomain()

    override fun findByCustomerId(customerId: CustomerId): List<CustomerTag> =
        jpaRepository.findByCustomerId(customerId.value).map { it.toDomain() }

    override fun findByCustomerIdIn(customerIds: Collection<CustomerId>): List<CustomerTag> =
        jpaRepository.findByCustomerIdIn(customerIds.map { it.value }).map { it.toDomain() }

    override fun deleteById(id: CustomerTagId) = jpaRepository.deleteById(id.value)

    private fun CustomerTagJpaEntity.toDomain(): CustomerTag = CustomerTag(
        id = CustomerTagId(id),
        customerId = CustomerId(customerId),
        label = label,
        createdAt = createdAt ?: Instant.EPOCH,
    )
}
