package ai.rojan.backend.application.schedule

import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.schedule.BlockId
import ai.rojan.backend.domain.schedule.LeaveId
import ai.rojan.backend.domain.schedule.ScheduleOverrideId
import ai.rojan.backend.domain.schedule.SpecialistBlock
import ai.rojan.backend.domain.schedule.SpecialistBlockRepository
import ai.rojan.backend.domain.schedule.SpecialistLeave
import ai.rojan.backend.domain.schedule.SpecialistLeaveRepository
import ai.rojan.backend.domain.schedule.SpecialistScheduleOverride
import ai.rojan.backend.domain.schedule.SpecialistScheduleOverrideRepository
import ai.rojan.backend.domain.schedule.SpecialistWeeklyAvailability
import ai.rojan.backend.domain.schedule.SpecialistWeeklyAvailabilityRepository
import ai.rojan.backend.domain.schedule.WeeklyAvailabilityId
import ai.rojan.backend.domain.schedule.WorkingHours
import ai.rojan.backend.domain.schedule.WorkingHoursId
import ai.rojan.backend.domain.schedule.WorkingHoursRepository
import java.time.DayOfWeek
import java.time.LocalDate

internal class InMemoryWorkingHoursRepository : WorkingHoursRepository {
    private val store = mutableMapOf<WorkingHoursId, WorkingHours>()
    override fun save(workingHours: WorkingHours): WorkingHours = workingHours.also { store[it.id] = it }
    override fun findById(id: WorkingHoursId): WorkingHours? = store[id]
    override fun findBySalonId(salonId: ai.rojan.backend.domain.salon.SalonId): List<WorkingHours> =
        store.values.filter { it.salonId == salonId }
    override fun findBySalonIdAndDayOfWeek(salonId: ai.rojan.backend.domain.salon.SalonId, dayOfWeek: DayOfWeek): WorkingHours? =
        store.values.find { it.salonId == salonId && it.dayOfWeek == dayOfWeek }
    override fun deleteBySalonIdAndDayOfWeek(salonId: ai.rojan.backend.domain.salon.SalonId, dayOfWeek: DayOfWeek) {
        findBySalonIdAndDayOfWeek(salonId, dayOfWeek)?.let { store.remove(it.id) }
    }
}

internal class InMemoryWeeklyAvailabilityRepository : SpecialistWeeklyAvailabilityRepository {
    private val store = mutableMapOf<WeeklyAvailabilityId, SpecialistWeeklyAvailability>()
    override fun save(availability: SpecialistWeeklyAvailability): SpecialistWeeklyAvailability =
        availability.also { store[it.id] = it }
    override fun findById(id: WeeklyAvailabilityId): SpecialistWeeklyAvailability? = store[id]
    override fun findBySpecialistId(specialistId: SpecialistId): List<SpecialistWeeklyAvailability> =
        store.values.filter { it.specialistId == specialistId }
    override fun findBySpecialistIdAndDayOfWeek(specialistId: SpecialistId, dayOfWeek: DayOfWeek): SpecialistWeeklyAvailability? =
        store.values.find { it.specialistId == specialistId && it.dayOfWeek == dayOfWeek }
    override fun deleteBySpecialistIdAndDayOfWeek(specialistId: SpecialistId, dayOfWeek: DayOfWeek) {
        findBySpecialistIdAndDayOfWeek(specialistId, dayOfWeek)?.let { store.remove(it.id) }
    }
}

internal class InMemoryScheduleOverrideRepository : SpecialistScheduleOverrideRepository {
    private val store = mutableMapOf<ScheduleOverrideId, SpecialistScheduleOverride>()
    override fun save(override: SpecialistScheduleOverride): SpecialistScheduleOverride = override.also { store[it.id] = it }
    override fun findById(id: ScheduleOverrideId): SpecialistScheduleOverride? = store[id]
    override fun findBySpecialistId(specialistId: SpecialistId): List<SpecialistScheduleOverride> =
        store.values.filter { it.specialistId == specialistId }
    override fun findBySpecialistIdAndDate(specialistId: SpecialistId, date: LocalDate): SpecialistScheduleOverride? =
        store.values.find { it.specialistId == specialistId && it.date == date }
    override fun deleteById(id: ScheduleOverrideId) {
        store.remove(id)
    }
}

internal class InMemoryLeaveRepository : SpecialistLeaveRepository {
    private val store = mutableMapOf<LeaveId, SpecialistLeave>()
    override fun save(leave: SpecialistLeave): SpecialistLeave = leave.also { store[it.id] = it }
    override fun findById(id: LeaveId): SpecialistLeave? = store[id]
    override fun findBySpecialistId(specialistId: SpecialistId): List<SpecialistLeave> =
        store.values.filter { it.specialistId == specialistId }
    override fun findBySpecialistIdCoveringDate(specialistId: SpecialistId, date: LocalDate): List<SpecialistLeave> =
        store.values.filter { it.specialistId == specialistId && it.overlaps(date) }
    override fun deleteById(id: LeaveId) {
        store.remove(id)
    }
}

internal class InMemoryBlockRepository : SpecialistBlockRepository {
    private val store = mutableMapOf<BlockId, SpecialistBlock>()
    override fun save(block: SpecialistBlock): SpecialistBlock = block.also { store[it.id] = it }
    override fun findById(id: BlockId): SpecialistBlock? = store[id]
    override fun findBySpecialistId(specialistId: SpecialistId): List<SpecialistBlock> =
        store.values.filter { it.specialistId == specialistId }
    override fun findBySpecialistIdAndDate(specialistId: SpecialistId, date: LocalDate): List<SpecialistBlock> =
        store.values.filter { it.specialistId == specialistId && it.date == date }
    override fun deleteById(id: BlockId) {
        store.remove(id)
    }
}
