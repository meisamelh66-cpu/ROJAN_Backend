package ai.rojan.backend.application.booking

import ai.rojan.backend.application.auth.RecordingRateLimiter
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
import ai.rojan.backend.domain.common.BookingRateLimitExceededException
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
import java.time.DayOfWeek
import java.time.Duration
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

    private val createUseCase = CreateBookingUseCase(salonRepository, serviceRepository, specialistRepository, bookingRepository, specialistServiceRepository, availabilityValidator, RecordingRateLimiter())
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

    // ---- D11: public booking creation rate limiting (owner decision: 10 per customer / 15m, 30
    // per IP / 15m, dual-key, both budgets always consumed) - uses its own CreateBookingUseCase
    // instance backed by CountingRateLimiterTestFixture (a real counting/windowed RateLimiterPort
    // test double), separate from the shared `createUseCase` above (which stays wired to the
    // permissive RecordingRateLimiter so every other test in this file is unaffected - D11's own
    // acceptance criterion for "existing behavior below threshold unchanged"). ----

    private fun rateLimitedCreateUseCase(limiter: CountingRateLimiterTestFixture) = CreateBookingUseCase(
        salonRepository, serviceRepository, specialistRepository, bookingRepository, specialistServiceRepository, availabilityValidator, limiter,
    )

    /** D11 slot spacing can exceed 24h for the 30-request IP scenario - open every day of week, not just [start]'s own (already configured in the shared `init` above), so a slot never fails on unrelated-to-rate-limiting unavailability. */
    private fun openEveryDayForD11() {
        val allDay = TimeInterval(LocalTime.of(0, 0), LocalTime.MAX)
        DayOfWeek.entries.forEach { day ->
            workingHoursRepository.save(WorkingHours.create(salon.id, day, listOf(allDay)))
            weeklyAvailabilityRepository.save(SpecialistWeeklyAvailability.create(specialist.id, day, listOf(allDay)))
        }
    }

    /** One booking per calendar day (always 09:00-09:30) rather than packing same-day slots - simplest way to guarantee zero overlap across up to 31 requests without ever crossing midnight (which [ai.rojan.backend.application.booking.SpecialistAvailabilityValidator] doesn't treat as still-available on the next day). [openEveryDayForD11] already covers every day of week this can land on. */
    private fun d11Command(customerId: UserId, slot: Int, callerIp: String?) = CreateBookingCommand(
        salon.id, service.id, specialist.id, customerId,
        LocalDateTime.of(start.toLocalDate().plusDays(slot.toLong()), LocalTime.of(9, 0)),
        "D11 rate-limit test", callerIp,
    )

    @Test
    fun `D11 - up to 9 requests under the per-customer limit of 10 are all allowed`() {
        openEveryDayForD11()
        val useCase = rateLimitedCreateUseCase(CountingRateLimiterTestFixture())
        repeat(9) { i ->
            assertEquals(BookingStatus.PENDING, useCase.execute(d11Command(customer, i, "10.0.0.1")).status)
        }
    }

    @Test
    fun `D11 - the 10th request for the same customer is still allowed - the limit is inclusive`() {
        openEveryDayForD11()
        val useCase = rateLimitedCreateUseCase(CountingRateLimiterTestFixture())
        repeat(10) { i ->
            assertEquals(BookingStatus.PENDING, useCase.execute(d11Command(customer, i, "10.0.0.2")).status)
        }
    }

    @Test
    fun `D11 - the 11th request for the same customer within 15 minutes is rate limited`() {
        openEveryDayForD11()
        val useCase = rateLimitedCreateUseCase(CountingRateLimiterTestFixture())
        repeat(10) { i -> useCase.execute(d11Command(customer, i, "10.0.0.3")) }
        assertThrows(BookingRateLimitExceededException::class.java) {
            useCase.execute(d11Command(customer, 10, "10.0.0.3"))
        }
    }

    @Test
    fun `D11 - up to 29 requests for the same IP, from 29 different customers, are all allowed`() {
        openEveryDayForD11()
        val useCase = rateLimitedCreateUseCase(CountingRateLimiterTestFixture())
        repeat(29) { i ->
            assertEquals(BookingStatus.PENDING, useCase.execute(d11Command(UserId.new(), i, "10.0.0.4")).status)
        }
    }

    @Test
    fun `D11 - the 31st request for the same IP is rate limited even from a 31st, brand-new customer who never called before`() {
        openEveryDayForD11()
        val useCase = rateLimitedCreateUseCase(CountingRateLimiterTestFixture())
        repeat(30) { i -> useCase.execute(d11Command(UserId.new(), i, "10.0.0.5")) }
        assertThrows(BookingRateLimitExceededException::class.java) {
            useCase.execute(d11Command(UserId.new(), 30, "10.0.0.5"))
        }
    }

    @Test
    fun `D11 - an exhausted customer budget still rate-limits even though the IP budget has plenty of headroom`() {
        openEveryDayForD11()
        val useCase = rateLimitedCreateUseCase(CountingRateLimiterTestFixture())
        repeat(10) { i -> useCase.execute(d11Command(customer, i, "10.0.0.6")) }
        // The shared IP has only consumed 10 of its 30-request budget here - the rejection below is
        // purely the customer key, proving the two budgets are checked independently.
        assertThrows(BookingRateLimitExceededException::class.java) {
            useCase.execute(d11Command(customer, 10, "10.0.0.6"))
        }
    }

    @Test
    fun `D11 - an exhausted IP budget still rate-limits a brand-new customer whose own budget is untouched`() {
        openEveryDayForD11()
        val useCase = rateLimitedCreateUseCase(CountingRateLimiterTestFixture())
        repeat(30) { i -> useCase.execute(d11Command(UserId.new(), i, "10.0.0.7")) }
        val freshCustomer = UserId.new()
        assertThrows(BookingRateLimitExceededException::class.java) {
            useCase.execute(d11Command(freshCustomer, 30, "10.0.0.7"))
        }
    }

    @Test
    fun `D11 - customer and IP keys are independent and never collide - exhausting one customer-IP pair never affects a different pair`() {
        openEveryDayForD11()
        val useCase = rateLimitedCreateUseCase(CountingRateLimiterTestFixture())
        repeat(10) { i -> useCase.execute(d11Command(customer, i, "10.0.0.8")) }
        assertThrows(BookingRateLimitExceededException::class.java) {
            useCase.execute(d11Command(customer, 10, "10.0.0.8"))
        }

        val unrelatedCustomer = UserId.new()
        assertEquals(BookingStatus.PENDING, useCase.execute(d11Command(unrelatedCustomer, 11, "10.0.0.9")).status)
    }

    @Test
    fun `D11 - after the 15-minute window elapses, the same customer is allowed again`() {
        openEveryDayForD11()
        val limiter = CountingRateLimiterTestFixture()
        val useCase = rateLimitedCreateUseCase(limiter)
        repeat(10) { i -> useCase.execute(d11Command(customer, i, "10.0.0.10")) }
        assertThrows(BookingRateLimitExceededException::class.java) {
            useCase.execute(d11Command(customer, 10, "10.0.0.10"))
        }

        limiter.advanceTimeBy(Duration.ofMinutes(15).plusSeconds(1))

        assertEquals(BookingStatus.PENDING, useCase.execute(d11Command(customer, 11, "10.0.0.10")).status)
    }
}
