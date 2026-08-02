package ai.rojan.backend.application.booking

import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.application.salon.InMemoryServiceCategoryRepository
import ai.rojan.backend.application.salon.InMemoryServiceRepository
import ai.rojan.backend.application.salon.InMemorySpecialistRepository
import ai.rojan.backend.application.schedule.InMemoryBlockRepository
import ai.rojan.backend.application.schedule.InMemoryLeaveRepository
import ai.rojan.backend.application.schedule.InMemoryScheduleOverrideRepository
import ai.rojan.backend.application.schedule.InMemoryWeeklyAvailabilityRepository
import ai.rojan.backend.application.schedule.InMemoryWorkingHoursRepository
import ai.rojan.backend.domain.booking.TimeSlot
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.Service
import ai.rojan.backend.domain.salon.ServiceCategory
import ai.rojan.backend.domain.salon.Specialist
import ai.rojan.backend.domain.schedule.SpecialistBlock
import ai.rojan.backend.domain.schedule.SpecialistLeave
import ai.rojan.backend.domain.schedule.SpecialistScheduleOverride
import ai.rojan.backend.domain.schedule.SpecialistWeeklyAvailability
import ai.rojan.backend.domain.schedule.TimeInterval
import ai.rojan.backend.domain.schedule.WorkingHours
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class GetAvailableSlotsUseCaseTest {

    private val salonRepository = InMemorySalonRepository()
    private val categoryRepository = InMemoryServiceCategoryRepository()
    private val serviceRepository = InMemoryServiceRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val workingHoursRepository = InMemoryWorkingHoursRepository()
    private val weeklyAvailabilityRepository = InMemoryWeeklyAvailabilityRepository()
    private val overrideRepository = InMemoryScheduleOverrideRepository()
    private val leaveRepository = InMemoryLeaveRepository()
    private val blockRepository = InMemoryBlockRepository()
    private val bookingRepository = InMemoryBookingRepository()

    private val salon: Salon = salonRepository.save(Salon.create(UserId.new(), "Glow Salon", null, "+1 555 0100", null, "1 Main St"))
    private val category: ServiceCategory = categoryRepository.save(ServiceCategory.create(salon.id, "Hair", null))
    private val service: Service = serviceRepository.save(Service.create(salon.id, category.id, "Haircut", null, 30, BigDecimal("25.00")))
    private val specialist: Specialist = specialistRepository.save(Specialist.create(salon.id, null, "Jamie", null, null))

    // A Monday far enough in the future that "earliestStart" never trims it in tests.
    private val monday = LocalDate.of(2026, 8, 10).let { it.plusDays((DayOfWeek.MONDAY.value - it.dayOfWeek.value + 7L) % 7) }

    private val useCase = GetAvailableSlotsUseCase(
        specialistRepository,
        serviceRepository,
        workingHoursRepository,
        weeklyAvailabilityRepository,
        overrideRepository,
        leaveRepository,
        blockRepository,
        bookingRepository,
        now = { LocalDate.of(2000, 1, 1) },
    )

    private fun query(date: LocalDate = monday, slotIntervalMinutes: Int = 30) =
        GetAvailableSlotsQuery(salon.id, specialist.id, service.id, date, slotIntervalMinutes)

    @Test
    fun `no slots when the salon has no working hours configured for that day`() {
        weeklyAvailabilityRepository.save(SpecialistWeeklyAvailability.create(specialist.id, DayOfWeek.MONDAY, listOf(TimeInterval(LocalTime.of(9, 0), LocalTime.of(17, 0)))))

        assertEquals(emptyList<TimeSlot>(), useCase.execute(query()))
    }

    @Test
    fun `no slots when the specialist has no weekly availability for that day`() {
        workingHoursRepository.save(WorkingHours.create(salon.id, DayOfWeek.MONDAY, listOf(TimeInterval(LocalTime.of(9, 0), LocalTime.of(17, 0)))))

        assertEquals(emptyList<TimeSlot>(), useCase.execute(query()))
    }

    @Test
    fun `slots are the intersection of salon hours and specialist availability`() {
        workingHoursRepository.save(WorkingHours.create(salon.id, DayOfWeek.MONDAY, listOf(TimeInterval(LocalTime.of(9, 0), LocalTime.of(17, 0)))))
        weeklyAvailabilityRepository.save(SpecialistWeeklyAvailability.create(specialist.id, DayOfWeek.MONDAY, listOf(TimeInterval(LocalTime.of(15, 0), LocalTime.of(20, 0)))))

        val slots = useCase.execute(query())

        assertEquals(
            listOf(monday.atTime(15, 0), monday.atTime(15, 30), monday.atTime(16, 0), monday.atTime(16, 30)),
            slots.map { it.start },
        )
        assertTrue(slots.all { it.end.toLocalTime() <= LocalTime.of(17, 0) })
    }

    @Test
    fun `an override replaces the normal weekly availability for that date`() {
        workingHoursRepository.save(WorkingHours.create(salon.id, DayOfWeek.MONDAY, listOf(TimeInterval(LocalTime.of(9, 0), LocalTime.of(17, 0)))))
        weeklyAvailabilityRepository.save(SpecialistWeeklyAvailability.create(specialist.id, DayOfWeek.MONDAY, listOf(TimeInterval(LocalTime.of(9, 0), LocalTime.of(17, 0)))))
        overrideRepository.save(SpecialistScheduleOverride.create(specialist.id, monday, listOf(TimeInterval(LocalTime.of(10, 0), LocalTime.of(11, 0))), "Reduced hours"))

        val slots = useCase.execute(query())

        assertEquals(listOf(monday.atTime(10, 0), monday.atTime(10, 30)), slots.map { it.start })
    }

    @Test
    fun `a leave covering the date makes the specialist fully unavailable`() {
        workingHoursRepository.save(WorkingHours.create(salon.id, DayOfWeek.MONDAY, listOf(TimeInterval(LocalTime.of(9, 0), LocalTime.of(17, 0)))))
        weeklyAvailabilityRepository.save(SpecialistWeeklyAvailability.create(specialist.id, DayOfWeek.MONDAY, listOf(TimeInterval(LocalTime.of(9, 0), LocalTime.of(17, 0)))))
        leaveRepository.save(SpecialistLeave.create(specialist.id, monday.minusDays(1), monday.plusDays(1), "Vacation"))

        assertEquals(emptyList<TimeSlot>(), useCase.execute(query()))
    }

    @Test
    fun `a manual block removes just that window`() {
        workingHoursRepository.save(WorkingHours.create(salon.id, DayOfWeek.MONDAY, listOf(TimeInterval(LocalTime.of(9, 0), LocalTime.of(11, 0)))))
        weeklyAvailabilityRepository.save(SpecialistWeeklyAvailability.create(specialist.id, DayOfWeek.MONDAY, listOf(TimeInterval(LocalTime.of(9, 0), LocalTime.of(11, 0)))))
        blockRepository.save(SpecialistBlock.create(specialist.id, monday, TimeInterval(LocalTime.of(9, 30), LocalTime.of(10, 30)), "Personal"))

        val slots = useCase.execute(query())

        assertEquals(listOf(monday.atTime(9, 0), monday.atTime(10, 30)), slots.map { it.start })
    }

    @Test
    fun `an existing booking removes the overlapping slot`() {
        workingHoursRepository.save(WorkingHours.create(salon.id, DayOfWeek.MONDAY, listOf(TimeInterval(LocalTime.of(9, 0), LocalTime.of(10, 0)))))
        weeklyAvailabilityRepository.save(SpecialistWeeklyAvailability.create(specialist.id, DayOfWeek.MONDAY, listOf(TimeInterval(LocalTime.of(9, 0), LocalTime.of(10, 0)))))
        val createBookingUseCase = CreateBookingUseCase(salonRepository, serviceRepository, specialistRepository, bookingRepository)
        createBookingUseCase.execute(CreateBookingCommand(salon.id, service.id, specialist.id, UserId.new(), monday.atTime(9, 0), null))

        val slots = useCase.execute(query())

        assertEquals(listOf(monday.atTime(9, 30)), slots.map { it.start })
    }

    @Test
    fun `a finer slotIntervalMinutes produces more candidate start times`() {
        workingHoursRepository.save(WorkingHours.create(salon.id, DayOfWeek.MONDAY, listOf(TimeInterval(LocalTime.of(9, 0), LocalTime.of(10, 0)))))
        weeklyAvailabilityRepository.save(SpecialistWeeklyAvailability.create(specialist.id, DayOfWeek.MONDAY, listOf(TimeInterval(LocalTime.of(9, 0), LocalTime.of(10, 0)))))

        val coarse = useCase.execute(query(slotIntervalMinutes = 30))
        val fine = useCase.execute(query(slotIntervalMinutes = 15))

        assertTrue(fine.size > coarse.size)
    }
}
