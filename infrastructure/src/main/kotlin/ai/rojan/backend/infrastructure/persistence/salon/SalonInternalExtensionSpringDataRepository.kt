package ai.rojan.backend.infrastructure.persistence.salon

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SalonInternalExtensionSpringDataRepository : JpaRepository<SalonInternalExtensionJpaEntity, UUID> {
    fun findBySalonId(salonId: UUID): List<SalonInternalExtensionJpaEntity>
    fun findByIdAndSalonId(id: UUID, salonId: UUID): SalonInternalExtensionJpaEntity?
    fun deleteByIdAndSalonId(id: UUID, salonId: UUID)
}
