package ai.rojan.backend.infrastructure.persistence.salon

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SalonFavoriteSpringDataRepository : JpaRepository<SalonFavoriteJpaEntity, UUID> {
    fun findByCustomerIdAndSalonId(customerId: UUID, salonId: UUID): SalonFavoriteJpaEntity?
    fun deleteByCustomerIdAndSalonId(customerId: UUID, salonId: UUID)
    fun findByCustomerId(customerId: UUID, pageable: Pageable): Page<SalonFavoriteJpaEntity>
}
