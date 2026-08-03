package ai.rojan.backend.domain.schedule

import ai.rojan.backend.domain.salon.SpecialistId
import java.time.DayOfWeek

interface SpecialistWeeklyAvailabilityRepository {
    fun save(availability: SpecialistWeeklyAvailability): SpecialistWeeklyAvailability
    fun findById(id: WeeklyAvailabilityId): SpecialistWeeklyAvailability?
    fun findBySpecialistId(specialistId: SpecialistId): List<SpecialistWeeklyAvailability>
    fun findBySpecialistIdAndDayOfWeek(specialistId: SpecialistId, dayOfWeek: DayOfWeek): SpecialistWeeklyAvailability?
    fun deleteBySpecialistIdAndDayOfWeek(specialistId: SpecialistId, dayOfWeek: DayOfWeek)
}
