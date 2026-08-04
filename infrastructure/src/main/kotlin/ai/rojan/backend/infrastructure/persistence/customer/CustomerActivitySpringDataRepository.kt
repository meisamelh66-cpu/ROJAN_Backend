package ai.rojan.backend.infrastructure.persistence.customer

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CustomerActivitySpringDataRepository : JpaRepository<CustomerActivityJpaEntity, UUID> {
    fun findByCustomerId(customerId: UUID): List<CustomerActivityJpaEntity>
}
