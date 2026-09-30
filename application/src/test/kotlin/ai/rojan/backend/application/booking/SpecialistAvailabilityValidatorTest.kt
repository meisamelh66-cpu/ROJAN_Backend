package ai.rojan.backend.application.booking

import ai.rojan.backend.application.schedule.InMemoryBlockRepository
import ai.rojan.backend.application.schedule.InMemoryLeaveRepository
import ai.rojan.backend.application.schedule.InMemoryScheduleOverrideRepository
import ai.rojan.backend.application.schedule.InMemoryWeeklyAvailabilityRepository
import ai.rojan.backend.application.schedule.InMemoryWorkingHoursRepository
import ai.rojan.backend.domain.common.SpecialistNotAvailableException
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.schedule.SpecialistBlock
import ai.rojan.backend.domain.schedule.SpecialistLeave
import ai.rojan.backend.domain.schedule.SpecialistScheduleOverride
import ai.rojan.backend.domain.schedule.SpecialistWeeklyAvailability
import ai.rojan.backend.domain.schedule.TimeInterval
import ai.rojan.backend.domain.schedule.WorkingHours
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Master Integration Repair, Pass 3: [SpecialistAvailabilityValidator] reuses the exact effective-
 * availability computation [GetAvailableSlotsUseCaseTest] already exercises for the slots-browsing
 * endpoint - these tests focus on the one-specific-window question [CreateBookingUseCase]/
 * [RescheduleBookingUseCase] actually need, not re-proving [ai.rojan.backend.domain.schedule.IntervalMath] itself.
 */
class SpecialistAvailabilityValidatorTest {

    private val workingHoursRepository = InMemoryWorkingHoursRepository()
    private val weeklyAvailabilityRepository = InMemoryWeeklyAvailabilityRepository()
    private val overrideRepository = InMemoryScheduleOverrideRepository()
    private val leaveRepository = InMemoryLeaveRepository()
    private val blockRepository = InMemoryBlockRepository()

    private val validator = SpecialistAvailabilityValidator(workingHoursRepository, weeklyAvailabilityRepository, overrideRepository, leaveRepository, blockRepository)

    private val salonId = SalonId.new()
    private val specialistId = SpecialistId.new()

    // A Monday, arbitrary but fixed.
    private val monday = LocalDate.of(2026, 8, 10).let { it.plusDays((DayOfWeek.MONDAY.value - it.dayOfWeek.value + 7L) % 7) }

    private fun givenWorkingHoursAndAvailability(hours: TimeInterval = TimeInterval(LocalTime.of(9, 0), LocalTime.of(17, 0))) {
        workingHoursRepository.save(WorkingHours.create(salonId, DayOfWeek.MONDAY, listOf(hours)))
        weeklyAvailabilityRepository.save(SpecialistWeeklyAvailability.create(specialistId, DayOfWeek.MONDAY, listOf(hours)))
    }

    @Test
    fun `a window fully inside salon hours and specialist availability is accepted`() {
        givenWorkingHoursAndAvailability()

        assertDoesNotThrow {
            validator.requireAvailable(salonId, specialistId, monday.atTime(10, 0), monday.atTime(10, 30))
        }
    }

    @Test
    fun `a window outside salon working hours is rejected`() {
        givenWorkingHoursAndAvailability(TimeInterval(LocalTime.of(9, 0), LocalTime.of(17, 0)))

        assertThrows<SpecialistNotAvailableException> {
            validator.requireAvailable(salonId, specialistId, monday.atTime(18, 0), monday.atTime(18, 30))
        }
    }

    @Test
    fun `a window outside the specialist's own weekly availability is rejected, even within salon hours`() {
        workingHoursRepository.save(WorkingHours.create(salonId, DayOfWeek.MONDAY, listOf(TimeInterval(LocalTime.of(9, 0), LocalTime.of(20, 0)))))
        weeklyAvailabilityRepository.save(SpecialistWeeklyAvailability.create(specialistId, DayOfWeek.MONDAY, listOf(TimeInterval(LocalTime.of(15, 0), LocalTime.of(17, 0)))))

        assertThrows<SpecialistNotAvailableException> {
            validator.requireAvailable(salonId, specialistId, monday.atTime(10, 0), monday.atTime(10, 30))
        }
    }

    @Test
    fun `no salon working hours configured for that day is rejected`() {
        weeklyAvailabilityRepository.save(SpecialistWeeklyAvailability.create(specialistId, DayOfWeek.MONDAY, listOf(TimeInterval(LocalTime.of(9, 0), LocalTime.of(17, 0)))))

        assertThrows<SpecialistNotAvailableException> {
            validator.requireAvailable(salonId, specialistId, monday.atTime(10, 0), monday.atTime(10, 30))
        }
    }

    @Test
    fun `a specialist on leave that day is rejected regardless of otherwise-open hours`() {
        givenWorkingHoursAndAvailability()
        leaveRepository.save(SpecialistLeave.create(specialistId, monday, monday, "Vacation"))

        assertThrows<SpecialistNotAvailableException> {
            validator.requireAvailable(salonId, specialistId, monday.atTime(10, 0), monday.atTime(10, 30))
        }
    }

    @Test
    fun `a window covered by a manual block is rejected`() {
        givenWorkingHoursAndAvailability()
        blockRepository.save(SpecialistBlock.create(specialistId, monday, TimeInterval(LocalTime.of(10, 0), LocalTime.of(11, 0)), "Lunch"))

        assertThrows<SpecialistNotAvailableException> {
            validator.requireAvailable(salonId, specialistId, monday.atTime(10, 15), monday.atTime(10, 45))
        }
    }

    @Test
    fun `a schedule override for that specific date replaces the weekly availability entirely`() {
        workingHoursRepository.save(WorkingHours.create(salonId, DayOfWeek.MONDAY, listOf(TimeInterval(LocalTime.of(9, 0), LocalTime.of(20, 0)))))
        weeklyAvailabilityRepository.save(SpecialistWeeklyAvailability.create(specialistId, DayOfWeek.MONDAY, listOf(TimeInterval(LocalTime.of(9, 0), LocalTime.of(12, 0)))))
        overrideRepository.save(SpecialistScheduleOverride.create(specialistId, monday, listOf(TimeInterval(LocalTime.of(14, 0), LocalTime.of(18, 0))), null))

        // Would have been rejected under the normal weekly availability (09:00-12:00) - the override for this date takes over instead.
        assertDoesNotThrow {
            validator.requireAvailable(salonId, specialistId, monday.atTime(15, 0), monday.atTime(15, 30))
        }
        assertThrows<SpecialistNotAvailableException> {
            validator.requireAvailable(salonId, specialistId, monday.atTime(10, 0), monday.atTime(10, 30))
        }
    }

    @Test
    fun `a window that would cross midnight is rejected rather than partially validated`() {
        givenWorkingHoursAndAvailability(TimeInterval(LocalTime.of(0, 0), LocalTime.MAX))

        assertThrows<SpecialistNotAvailableException> {
            validator.requireAvailable(salonId, specialistId, monday.atTime(23, 45), monday.plusDays(1).atTime(0, 15))
        }
    }
}
