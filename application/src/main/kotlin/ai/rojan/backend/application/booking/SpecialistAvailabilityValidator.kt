package ai.rojan.backend.application.booking

import ai.rojan.backend.domain.common.SpecialistNotAvailableException
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.schedule.IntervalMath
import ai.rojan.backend.domain.schedule.SpecialistBlockRepository
import ai.rojan.backend.domain.schedule.SpecialistLeaveRepository
import ai.rojan.backend.domain.schedule.SpecialistScheduleOverrideRepository
import ai.rojan.backend.domain.schedule.SpecialistWeeklyAvailabilityRepository
import ai.rojan.backend.domain.schedule.TimeInterval
import ai.rojan.backend.domain.schedule.WorkingHoursRepository
import java.time.LocalDateTime

/**
 * Master Integration Repair, Pass 3 (Booking/Calendar): [CreateBookingUseCase]/[RescheduleBookingUseCase]
 * previously validated only specialist-service eligibility and existing-booking overlap
 * ([ai.rojan.backend.domain.booking.BookingRepository.reserve]'s advisory lock) before persisting a
 * booking - nothing checked salon working hours, specialist weekly availability, schedule overrides,
 * leave, or manual blocks, even though [GetAvailableSlotsUseCase] already computes exactly this for
 * the slots-browsing endpoint. A client honoring the slots endpoint would never hit this gap, but
 * nothing on the backend actually enforced it - a booking for an out-of-hours time, or a time during
 * a specialist's leave/block, would previously be silently accepted.
 *
 * This validator is that same effective-availability computation
 * (salon hours ∩ (override ?: weekly availability) − blocks, plus the leave check), applied to one
 * specific requested [LocalDateTime] window instead of generating a whole day's slots - reused, not
 * duplicated logic. It intentionally does NOT check existing-booking overlap - that remains
 * [ai.rojan.backend.domain.booking.BookingRepository.reserve]'s job alone (the advisory lock is the
 * only thing safe under concurrent requests; this validator's repository reads are not).
 */
class SpecialistAvailabilityValidator(
    private val workingHoursRepository: WorkingHoursRepository,
    private val weeklyAvailabilityRepository: SpecialistWeeklyAvailabilityRepository,
    private val overrideRepository: SpecialistScheduleOverrideRepository,
    private val leaveRepository: SpecialistLeaveRepository,
    private val blockRepository: SpecialistBlockRepository,
) {
    fun requireAvailable(salonId: SalonId, specialistId: SpecialistId, startTime: LocalDateTime, endTime: LocalDateTime) {
        val date = startTime.toLocalDate()

        // A booking that would cross midnight can never fit a single day's working-hours/
        // availability model below - reject rather than silently allow an unvalidated window.
        if (endTime.toLocalDate() != date) {
            throw notAvailable(specialistId, startTime, endTime)
        }

        if (leaveRepository.findBySpecialistIdCoveringDate(specialistId, date).isNotEmpty()) {
            throw notAvailable(specialistId, startTime, endTime)
        }

        val salonHours = workingHoursRepository.findBySalonIdAndDayOfWeek(salonId, date.dayOfWeek)?.intervals
            ?: throw notAvailable(specialistId, startTime, endTime)

        val override = overrideRepository.findBySpecialistIdAndDate(specialistId, date)
        val baseAvailability = override?.intervals
            ?: weeklyAvailabilityRepository.findBySpecialistIdAndDayOfWeek(specialistId, date.dayOfWeek)?.intervals
            ?: emptyList()
        if (baseAvailability.isEmpty()) throw notAvailable(specialistId, startTime, endTime)

        val blocks = blockRepository.findBySpecialistIdAndDate(specialistId, date).map { it.interval }
        val effectiveAvailability = IntervalMath.subtract(IntervalMath.intersect(salonHours, baseAvailability), blocks)

        val requested = TimeInterval(startTime.toLocalTime(), endTime.toLocalTime())
        val fits = effectiveAvailability.any { it.start <= requested.start && requested.end <= it.end }
        if (!fits) throw notAvailable(specialistId, startTime, endTime)
    }

    private fun notAvailable(specialistId: SpecialistId, startTime: LocalDateTime, endTime: LocalDateTime) =
        SpecialistNotAvailableException(specialistId.value.toString(), startTime.toString(), endTime.toString())
}
