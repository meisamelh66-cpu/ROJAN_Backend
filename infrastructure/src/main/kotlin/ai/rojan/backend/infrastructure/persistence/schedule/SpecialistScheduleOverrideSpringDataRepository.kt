package ai.rojan.backend.infrastructure.persistence.schedule

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.UUID

interface SpecialistScheduleOverrideSpringDataRepository : JpaRepository<SpecialistScheduleOverrideJpaEntity, UUID> {
    fun findBySpecialistId(specialistId: UUID): List<SpecialistScheduleOverrideJpaEntity>
    fun findBySpecialistIdAndOverrideDate(specialistId: UUID, overrideDate: LocalDate): SpecialistScheduleOverrideJpaEntity?
}
