package ai.rojan.backend.infrastructure.persistence.salon

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SpecialistServiceSpringDataRepository : JpaRepository<SpecialistServiceJpaEntity, UUID> {
    fun findBySpecialistId(specialistId: UUID): List<SpecialistServiceJpaEntity>
    fun findBySpecialistIdAndServiceId(specialistId: UUID, serviceId: UUID): SpecialistServiceJpaEntity?
    fun deleteBySpecialistIdAndServiceId(specialistId: UUID, serviceId: UUID)
}
