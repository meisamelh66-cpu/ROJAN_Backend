package ai.rojan.backend.infrastructure.persistence.schedule

import org.springframework.data.jpa.repository.JpaRepository
import java.time.DayOfWeek
import java.util.UUID

interface SpecialistWeeklyAvailabilitySpringDataRepository : JpaRepository<SpecialistWeeklyAvailabilityJpaEntity, UUID> {
    fun findBySpecialistId(specialistId: UUID): List<SpecialistWeeklyAvailabilityJpaEntity>
    fun findBySpecialistIdAndDayOfWeek(specialistId: UUID, dayOfWeek: DayOfWeek): SpecialistWeeklyAvailabilityJpaEntity?
}
