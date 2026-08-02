package ai.rojan.backend.application.booking

import ai.rojan.backend.domain.booking.BookingRepository
import ai.rojan.backend.domain.booking.TimeSlot
import ai.rojan.backend.domain.booking.TimeSlotEngine
import ai.rojan.backend.domain.common.ServiceNotFoundException
import ai.rojan.backend.domain.common.SpecialistNotFoundException
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.ServiceId
import ai.rojan.backend.domain.salon.ServiceRepository
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.salon.SpecialistRepository
import ai.rojan.backend.domain.schedule.IntervalMath
import ai.rojan.backend.domain.schedule.SpecialistBlockRepository
import ai.rojan.backend.domain.schedule.SpecialistLeaveRepository
import ai.rojan.backend.domain.schedule.SpecialistScheduleOverrideRepository
import ai.rojan.backend.domain.schedule.SpecialistWeeklyAvailabilityRepository
import ai.rojan.backend.domain.schedule.TimeInterval
import ai.rojan.backend.domain.schedule.WorkingHoursRepository
import java.time.LocalDate
import java.time.LocalTime

data class GetAvailableSlotsQuery(
    val salonId: SalonId,
    val specialistId: SpecialistId,
    val serviceId: ServiceId,
    val date: LocalDate,
    val slotIntervalMinutes: Int,
)

/**
 * Orchestrates the schedule model (salon hours, specialist weekly
 * availability, overrides, leave, manual blocks, and existing bookings) into
 * the free windows the pure [TimeSlotEngine] turns into bookable slots.
 */
class GetAvailableSlotsUseCase(
    private val specialistRepository: SpecialistRepository,
    private val serviceRepository: ServiceRepository,
    private val workingHoursRepository: WorkingHoursRepository,
    private val weeklyAvailabilityRepository: SpecialistWeeklyAvailabilityRepository,
    private val overrideRepository: SpecialistScheduleOverrideRepository,
    private val leaveRepository: SpecialistLeaveRepository,
    private val blockRepository: SpecialistBlockRepository,
    private val bookingRepository: BookingRepository,
    private val now: () -> LocalDate = { LocalDate.now() },
    private val currentTime: () -> LocalTime = { LocalTime.now() },
) {
    fun execute(query: GetAvailableSlotsQuery): List<TimeSlot> {
        val specialist = specialistRepository.findById(query.specialistId)
            ?.takeIf { it.salonId == query.salonId && it.active }
            ?: throw SpecialistNotFoundException(query.specialistId.value.toString())
        val service = serviceRepository.findById(query.serviceId)
            ?.takeIf { it.salonId == query.salonId && it.active }
            ?: throw ServiceNotFoundException(query.serviceId.value.toString())

        if (leaveRepository.findBySpecialistIdCoveringDate(specialist.id, query.date).isNotEmpty()) {
            return emptyList()
        }

        val salonHours = workingHoursRepository.findBySalonIdAndDayOfWeek(query.salonId, query.date.dayOfWeek)
            ?.intervals ?: return emptyList()

        val override = overrideRepository.findBySpecialistIdAndDate(specialist.id, query.date)
        val baseAvailability = override?.intervals
            ?: weeklyAvailabilityRepository.findBySpecialistIdAndDayOfWeek(specialist.id, query.date.dayOfWeek)?.intervals
            ?: emptyList()
        if (baseAvailability.isEmpty()) return emptyList()

        val blocks = blockRepository.findBySpecialistIdAndDate(specialist.id, query.date).map { it.interval }
        val effectiveAvailability = IntervalMath.subtract(IntervalMath.intersect(salonHours, baseAvailability), blocks)
        if (effectiveAvailability.isEmpty()) return emptyList()

        val dayStart = query.date.atStartOfDay()
        val dayEnd = query.date.plusDays(1).atStartOfDay()
        val existingBookings = bookingRepository.findActiveBySpecialistIdAndDateRange(specialist.id, dayStart, dayEnd)
        val busyIntervals = existingBookings.mapNotNull { booking ->
            val clippedStart = maxOf(booking.startTime, dayStart).toLocalTime()
            val clippedEnd = if (booking.endTime >= dayEnd) LocalTime.MAX else booking.endTime.toLocalTime()
            if (clippedStart < clippedEnd) TimeInterval(clippedStart, clippedEnd) else null
        }

        val earliestStart = if (query.date == now()) currentTime() else null

        return TimeSlotEngine.generateSlots(
            date = query.date,
            availableIntervals = effectiveAvailability,
            busyIntervals = busyIntervals,
            serviceDurationMinutes = service.durationMinutes,
            slotIntervalMinutes = query.slotIntervalMinutes,
            earliestStart = earliestStart,
        )
    }
}
