package ai.rojan.backend.infrastructure.persistence.schedule

import org.springframework.data.jpa.repository.JpaRepository
import java.time.DayOfWeek
import java.util.UUID

interface WorkingHoursSpringDataRepository : JpaRepository<WorkingHoursJpaEntity, UUID> {
    fun findBySalonId(salonId: UUID): List<WorkingHoursJpaEntity>
    fun findBySalonIdAndDayOfWeek(salonId: UUID, dayOfWeek: DayOfWeek): WorkingHoursJpaEntity?
}
