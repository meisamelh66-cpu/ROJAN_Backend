package ai.rojan.backend.domain.schedule

import ai.rojan.backend.domain.salon.SalonId
import java.time.DayOfWeek

interface WorkingHoursRepository {
    fun save(workingHours: WorkingHours): WorkingHours
    fun findById(id: WorkingHoursId): WorkingHours?
    fun findBySalonId(salonId: SalonId): List<WorkingHours>
    fun findBySalonIdAndDayOfWeek(salonId: SalonId, dayOfWeek: DayOfWeek): WorkingHours?
    fun deleteBySalonIdAndDayOfWeek(salonId: SalonId, dayOfWeek: DayOfWeek)
}
