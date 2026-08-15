package ai.rojan.backend.infrastructure.persistence.salon

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SpecialistSpringDataRepository : JpaRepository<SpecialistJpaEntity, UUID> {
    fun findBySalonId(salonId: UUID): List<SpecialistJpaEntity>
    fun findBySalonIdAndUserId(salonId: UUID, userId: UUID): SpecialistJpaEntity?
    fun findByUserId(userId: UUID): List<SpecialistJpaEntity>
}
