package ai.rojan.backend.domain.schedule

import ai.rojan.backend.domain.salon.SpecialistId
import java.time.LocalDate

interface SpecialistScheduleOverrideRepository {
    fun save(override: SpecialistScheduleOverride): SpecialistScheduleOverride
    fun findById(id: ScheduleOverrideId): SpecialistScheduleOverride?
    fun findBySpecialistId(specialistId: SpecialistId): List<SpecialistScheduleOverride>
    fun findBySpecialistIdAndDate(specialistId: SpecialistId, date: LocalDate): SpecialistScheduleOverride?
    fun deleteById(id: ScheduleOverrideId)
}
