package ai.rojan.backend.infrastructure.persistence.salon

import ai.rojan.backend.domain.salon.SalonFollowStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SalonFollowSpringDataRepository : JpaRepository<SalonFollowJpaEntity, UUID> {
    fun findByCustomerIdAndSalonId(customerId: UUID, salonId: UUID): SalonFollowJpaEntity?
    fun findByCustomerIdAndStatus(customerId: UUID, status: SalonFollowStatus, pageable: Pageable): Page<SalonFollowJpaEntity>
}
