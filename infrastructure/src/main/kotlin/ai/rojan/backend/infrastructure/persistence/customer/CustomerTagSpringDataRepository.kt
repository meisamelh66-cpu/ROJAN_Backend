package ai.rojan.backend.infrastructure.persistence.customer

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CustomerTagSpringDataRepository : JpaRepository<CustomerTagJpaEntity, UUID> {
    fun findByCustomerId(customerId: UUID): List<CustomerTagJpaEntity>
    fun findByCustomerIdIn(customerIds: Collection<UUID>): List<CustomerTagJpaEntity>
}
