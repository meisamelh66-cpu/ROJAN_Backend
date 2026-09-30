package ai.rojan.backend.application.booking

import ai.rojan.backend.application.salon.InMemorySalonMembershipRepository
import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.application.salon.InMemoryServiceCategoryRepository
import ai.rojan.backend.application.salon.InMemoryServiceRepository
import ai.rojan.backend.application.salon.InMemorySpecialistRepository
import ai.rojan.backend.application.salon.InMemorySpecialistServiceRepository
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.application.schedule.InMemoryBlockRepository
import ai.rojan.backend.application.schedule.InMemoryLeaveRepository
import ai.rojan.backend.application.schedule.InMemoryScheduleOverrideRepository
import ai.rojan.backend.application.schedule.InMemoryWeeklyAvailabilityRepository
import ai.rojan.backend.application.schedule.InMemoryWorkingHoursRepository
import ai.rojan.backend.domain.booking.BookingId
import ai.rojan.backend.domain.booking.BookingStatus
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.BookingConflictException
import ai.rojan.backend.domain.common.InvalidBookingStateException
import ai.rojan.backend.domain.common.SpecialistNotAvailableException
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.Service
import ai.rojan.backend.domain.salon.ServiceCategory
import ai.rojan.backend.domain.salon.Specialist
import ai.rojan.backend.domain.schedule.SpecialistWeeklyAvailability
import ai.rojan.backend.domain.schedule.TimeInterval
import ai.rojan.backend.domain.schedule.WorkingHours
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.LocalTime

class BookingUseCasesTest {

    private val salonRepository = InMemorySalonRepository()
    private val categoryRepository = InMemoryServiceCategoryRepository()
    private val serviceRepository = InMemoryServiceRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val bookingRepository = InMemoryBookingRepository()
    private val specialistServiceRepository = InMemorySpecialistServiceRepository()
    private val membershipRepository = InMemorySalonMembershipRepository()
    private val salonPermissionResolver = SalonPermissionResolver(salonRepository, membershipRepository, specialistRepository)
    private val workingHoursRepository = InMemoryWorkingHoursRepository()
    private val weeklyAvailabilityRepository = InMemoryWeeklyAvailabilityRepository()
    private val overrideRepository = InMemoryScheduleOverrideRepository()
    private val leaveRepository = InMemoryLeaveRepository()
    private val blockRepository = InMemoryBlockRepository()
    private val availabilityValidator = SpecialistAvailabilityValidator(workingHoursRepository, weeklyAvailabilityRepository, overrideRepository, leaveRepository, blockRepository)

    private val owner = UserId.new()
    private val customer = UserId.new()
    private val otherCustomer = UserId.new()

    private val salon: Salon = salonRepository.save(Salon.create(owner, "Glow Salon", null, "+1 555 0100", null, "1 Main St"))
    private val category: ServiceCategory = categoryRepository.save(ServiceCategory.create(salon.id, "Hair", null))
    private val service: Service = serviceRepository.save(
        Service.create(salon.id, category.id, "Haircut", null, 30, BigDecimal("25.00")),
    )
    private val specialist: Specialist = specialistRepository.save(Specialist.create(salon.id, null, "Jamie", null, null))

    private val createUseCase = CreateBookingUseCase(salonRepository, serviceRepository, specialistRepository, bookingRepository, specialistServiceRepository, availabilityValidator)
    private val confirmUseCase = ConfirmBookingUseCase(bookingRepository, salonPermissionResolver)
    private val cancelUseCase = CancelBookingUseCase(bookingRepository, salonPermissionResolver)
    private val completeUseCase = CompleteBookingUseCase(bookingRepository, salonPermissionResolver)
    private val rescheduleUseCase = RescheduleBookingUseCase(bookingRepository, serviceRepository, salonPermissionResolver, availabilityValidator)

    private val start = LocalDateTime.of(2026, 8, 10, 9, 0)

    init {
        // Master Integration Repair, Pass 3: CreateBookingUseCase/RescheduleBookingUseCase now
        // validate working hours/specialist availability (see SpecialistAvailabilityValidator) - wide
        // open for every test above that predates this check and never configured any, so their exact
        // pre-existing times keep working unchanged. Tests that specifically exercise the new
        // validation below configure their own narrower hours.
        val allDay = TimeInterval(LocalTime.of(0, 0), LocalTime.MAX)
        workingHoursRepository.save(WorkingHours.create(salon.id, start.dayOfWeek, listOf(allDay)))
        weeklyAvailabilityRepository.save(SpecialistWeeklyAvailability.create(specialist.id, start.dayOfWeek, listOf(allDay)))
    }

    private fun createBooking(customerId: UserId = customer, startTime: LocalDateTime = start) = createUseCase.execute(
        CreateBookingCommand(salon.id, service.id, specialist.id, customerId, startTime, "First visit"),
    )

    @Test
    fun `creates a pending booking with end time derived from service duration`() {
        val booking = createBooking()
        assertEquals(BookingStatus.PENDING, booking.status)
        assertEquals(start.plusMinutes(30), booking.endTime)
    }

    @Test
    fun `rejects a second overlapping booking for the same specialist`() {
        createBooking()
        assertThrows(BookingConflictException::class.java) {
            createBooking(customerId = otherCustomer, startTime = start.plusMinutes(15))
        }
    }

    @Test
    fun `a specialist with no service assignments is bookable for any service - backward compatible default`() {
        val booking = createBooking()
        assertEquals(BookingStatus.PENDING, booking.status)
    }

    @Test
    fun `a specialist assigned to a specific service can still be booked for it`() {
        specialistServiceRepository.assign(specialist.id, service.id)
        val booking = createBooking()
        assertEquals(BookingStatus.PENDING, booking.status)
    }

    @Test
    fun `a specialist assigned to a different service is rejected for an unassigned one`() {
        val otherService = serviceRepository.save(Service.create(salon.id, category.id, "Manicure", null, 30, BigDecimal("15.00")))
        specialistServiceRepository.assign(specialist.id, otherService.id)

        assertThrows(ai.rojan.backend.domain.common.SpecialistNotEligibleForServiceException::class.java) {
            createBooking()
        }
    }

    @Test
    fun `allows a back-to-back booking that does not overlap`() {
        createBooking()
        val second = createBooking(customerId = otherCustomer, startTime = start.plusMinutes(30))
        assertEquals(start.plusMinutes(30), second.startTime)
    }

    @Test
    fun `owner can confirm a pending booking`() {
        val booking = createBooking()
        val confirmed = confirmUseCase.execute(ConfirmBookingCommand(booking.id, owner))
        assertEquals(BookingStatus.CONFIRMED, confirmed.status)
    }

    @Test
    fun `rejects confirmation from the customer (owner only)`() {
        val booking = createBooking()
        assertThrows(SalonAccessDeniedException::class.java) {
            confirmUseCase.execute(ConfirmBookingCommand(booking.id, customer))
        }
    }

    @Test
    fun `confirming twice is rejected as an invalid state transition`() {
        val booking = createBooking()
        confirmUseCase.execute(ConfirmBookingCommand(booking.id, owner))
        assertThrows(InvalidBookingStateException::class.java) {
            confirmUseCase.execute(ConfirmBookingCommand(booking.id, owner))
        }
    }

    @Test
    fun `customer can cancel their own booking`() {
        val booking = createBooking()
        val cancelled = cancelUseCase.execute(CancelBookingCommand(booking.id, customer))
        assertEquals(BookingStatus.CANCELLED, cancelled.status)
    }

    @Test
    fun `owner can also cancel a booking on their salon`() {
        val booking = createBooking()
        val cancelled = cancelUseCase.execute(CancelBookingCommand(booking.id, owner))
        assertEquals(BookingStatus.CANCELLED, cancelled.status)
    }

    @Test
    fun `rejects cancellation from an unrelated user`() {
        val booking = createBooking()
        assertThrows(SalonAccessDeniedException::class.java) {
            cancelUseCase.execute(CancelBookingCommand(booking.id, otherCustomer))
        }
    }

    @Test
    fun `owner can complete a confirmed booking`() {
        val booking = createBooking()
        confirmUseCase.execute(ConfirmBookingCommand(booking.id, owner))
        val completed = completeUseCase.execute(CompleteBookingCommand(booking.id, owner))
        assertEquals(BookingStatus.COMPLETED, completed.status)
    }

    @Test
    fun `rejects completing a booking that was never confirmed`() {
        val booking = createBooking()
        assertThrows(InvalidBookingStateException::class.java) {
            completeUseCase.execute(CompleteBookingCommand(booking.id, owner))
        }
    }

    @Test
    fun `customer can reschedule to a free slot`() {
        val booking = createBooking()
        val newStart = start.plusHours(2)
        val rescheduled = rescheduleUseCase.execute(RescheduleBookingCommand(booking.id, customer, newStart))
        assertEquals(newStart, rescheduled.startTime)
        assertEquals(newStart.plusMinutes(30), rescheduled.endTime)
    }

    @Test
    fun `rescheduling to overlap another active booking is rejected`() {
        createBooking(customerId = otherCustomer, startTime = start.plusHours(2))
        val booking = createBooking()

        assertThrows(BookingConflictException::class.java) {
            rescheduleUseCase.execute(RescheduleBookingCommand(booking.id, customer, start.plusHours(2).plusMinutes(10)))
        }
    }

    @Test
    fun `rescheduling to the same slot does not conflict with itself`() {
        val booking = createBooking()
        val rescheduled = rescheduleUseCase.execute(RescheduleBookingCommand(booking.id, customer, start.plusMinutes(5)))
        assertEquals(start.plusMinutes(5), rescheduled.startTime)
    }

    @Test
    fun `unknown booking id yields a not-found error`() {
        assertThrows(ai.rojan.backend.domain.common.BookingNotFoundException::class.java) {
            confirmUseCase.execute(ConfirmBookingCommand(BookingId.new(), owner))
        }
    }

    // ---- Master Integration Repair, Pass 3: booking creation/reschedule now validates working
    // ---- hours/specialist availability (previously only overlap with other bookings was checked).

    @Test
    fun `rejects a new booking outside the salon's configured working hours`() {
        // Replace the wide-open default from init{} with a narrow real working-hours window - the
        // in-memory fakes key by id, not (salon, dayOfWeek), so the old wide-open row must be deleted
        // first or find() would keep returning it.
        workingHoursRepository.deleteBySalonIdAndDayOfWeek(salon.id, start.dayOfWeek)
        weeklyAvailabilityRepository.deleteBySpecialistIdAndDayOfWeek(specialist.id, start.dayOfWeek)
        workingHoursRepository.save(WorkingHours.create(salon.id, start.dayOfWeek, listOf(TimeInterval(LocalTime.of(9, 0), LocalTime.of(17, 0)))))
        weeklyAvailabilityRepository.save(SpecialistWeeklyAvailability.create(specialist.id, start.dayOfWeek, listOf(TimeInterval(LocalTime.of(9, 0), LocalTime.of(17, 0)))))

        assertThrows(SpecialistNotAvailableException::class.java) {
            createBooking(startTime = start.withHour(20))
        }
    }

    @Test
    fun `rejects a new booking while the specialist is on leave, even though the salon and specialist calendars are otherwise wide open`() {
        leaveRepository.save(ai.rojan.backend.domain.schedule.SpecialistLeave.create(specialist.id, start.toLocalDate(), start.toLocalDate(), "Vacation"))

        assertThrows(SpecialistNotAvailableException::class.java) {
            createBooking()
        }
    }

    @Test
    fun `rejects rescheduling a booking to a time outside the salon's configured working hours`() {
        val booking = createBooking()
        workingHoursRepository.deleteBySalonIdAndDayOfWeek(salon.id, start.dayOfWeek)
        weeklyAvailabilityRepository.deleteBySpecialistIdAndDayOfWeek(specialist.id, start.dayOfWeek)
        workingHoursRepository.save(WorkingHours.create(salon.id, start.dayOfWeek, listOf(TimeInterval(LocalTime.of(9, 0), LocalTime.of(17, 0)))))
        weeklyAvailabilityRepository.save(SpecialistWeeklyAvailability.create(specialist.id, start.dayOfWeek, listOf(TimeInterval(LocalTime.of(9, 0), LocalTime.of(17, 0)))))

        assertThrows(SpecialistNotAvailableException::class.java) {
            rescheduleUseCase.execute(RescheduleBookingCommand(booking.id, customer, start.withHour(20)))
        }
    }
}
