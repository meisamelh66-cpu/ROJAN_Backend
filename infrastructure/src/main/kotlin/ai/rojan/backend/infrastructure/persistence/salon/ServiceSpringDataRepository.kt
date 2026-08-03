package ai.rojan.backend.infrastructure.persistence.salon

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ServiceSpringDataRepository : JpaRepository<ServiceJpaEntity, UUID> {
    fun findByCategoryId(categoryId: UUID): List<ServiceJpaEntity>
    fun findBySalonId(salonId: UUID): List<ServiceJpaEntity>
}
