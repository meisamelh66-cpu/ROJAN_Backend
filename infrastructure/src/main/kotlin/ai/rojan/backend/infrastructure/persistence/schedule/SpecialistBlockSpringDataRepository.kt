package ai.rojan.backend.infrastructure.persistence.schedule

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.UUID

interface SpecialistBlockSpringDataRepository : JpaRepository<SpecialistBlockJpaEntity, UUID> {
    fun findBySpecialistId(specialistId: UUID): List<SpecialistBlockJpaEntity>
    fun findBySpecialistIdAndBlockDate(specialistId: UUID, blockDate: LocalDate): List<SpecialistBlockJpaEntity>
}
