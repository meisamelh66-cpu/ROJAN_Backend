package ai.rojan.backend.infrastructure.persistence.salon

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ServiceCategorySpringDataRepository : JpaRepository<ServiceCategoryJpaEntity, UUID> {
    fun findBySalonId(salonId: UUID): List<ServiceCategoryJpaEntity>
}
