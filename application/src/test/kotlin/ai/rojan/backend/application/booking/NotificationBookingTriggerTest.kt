package ai.rojan.backend.application.booking

import ai.rojan.backend.application.auth.RecordingRateLimiter
import ai.rojan.backend.application.notification.InMemoryNotificationRepository
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
import ai.rojan.backend.domain.common.InvalidBookingStateException
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.notification.NotificationType
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.Service
import ai.rojan.backend.domain.salon.ServiceCategory
import ai.rojan.backend.domain.salon.Specialist
import ai.rojan.backend.domain.schedule.SpecialistWeeklyAvailability
import ai.rojan.backend.domain.schedule.TimeInterval
import ai.rojan.backend.domain.schedule.WorkingHours
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.LocalTime

class NotificationBookingTriggerTest {

    private val salonRepository = InMemorySalonRepository()
    private val categoryRepository = InMemoryServiceCategoryRepository()
    private val serviceRepository = InMemoryServiceRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val bookingRepository = InMemoryBookingRepository()
    private val specialistServiceRepository = InMemorySpecialistServiceRepository()
    private val membershipRepository = InMemorySalonMembershipRepository()
    private val salonPermissionResolver = SalonPermissionResolver(salonRepository, membershipRepository, specialistRepository)
    private val notificationRepository = InMemoryNotificationRepository()

    private val workingHoursRepository = InMemoryWorkingHoursRepository()
    private val weeklyAvailabilityRepository = InMemoryWeeklyAvailabilityRepository()
    private val overrideRepository = InMemoryScheduleOverrideRepository()
    private val leaveRepository = InMemoryLeaveRepository()
    private val blockRepository = InMemoryBlockRepository()
    private val availabilityValidator = SpecialistAvailabilityValidator(
        workingHoursRepository,
        weeklyAvailabilityRepository,
        overrideRepository,
        leaveRepository,
        blockRepository,
    )

    private val owner = UserId.new()
    private val customer = UserId.new()

    private val salon: Salon = salonRepository.save(Salon.create(owner, "Bella Salon", null, "+989121111111", null, "Tehran")).also {
        it.activate()
        salonRepository.save(it)
    }
    private val category: ServiceCategory = categoryRepository.save(ServiceCategory.create(salon.id, "Hair", null))
    private val service: Service = serviceRepository.save(
        Service.create(salon.id, category.id, "Blowdry", null, 45, BigDecimal("50.00")),
    )
    private val specialist: Specialist = specialistRepository.save(Specialist.create(salon.id, null, "Sara", null, null))

    private val startTime = LocalDateTime.of(2026, 11, 1, 10, 0)

    init {
        val allDay = TimeInterval(LocalTime.of(0, 0), LocalTime.MAX)
        workingHoursRepository.save(WorkingHours.create(salon.id, startTime.dayOfWeek, listOf(allDay)))
        weeklyAvailabilityRepository.save(SpecialistWeeklyAvailability.create(specialist.id, startTime.dayOfWeek, listOf(allDay)))
    }

    private val createUseCase = CreateBookingUseCase(
        salonRepository,
        serviceRepository,
        specialistRepository,
        bookingRepository,
        specialistServiceRepository,
        availabilityValidator,
        RecordingRateLimiter(),
        notificationRepository,
    )

    private val cancelUseCase = CancelBookingUseCase(
        bookingRepository,
        salonPermissionResolver,
        notificationRepository,
    )

    private val confirmUseCase = ConfirmBookingUseCase(
        bookingRepository,
        salonPermissionResolver,
        notificationRepository,
    )

    private val completeUseCase = CompleteBookingUseCase(
        bookingRepository,
        salonPermissionResolver,
        notificationRepository,
    )

    @Test
    fun `successful booking creation generates exactly one BOOKING_CREATED notification`() {
        val booking = createUseCase.execute(
            CreateBookingCommand(salon.id, service.id, specialist.id, customer, startTime, "Special request"),
        )

        assertEquals(1L, notificationRepository.countUnreadBySalonId(salon.id))
        val notifications = notificationRepository.findBySalonId(salon.id, ai.rojan.backend.domain.common.PageRequest(0, 10)).content
        assertEquals(1, notifications.size)

        val notif = notifications[0]
        assertEquals(NotificationType.BOOKING_CREATED, notif.type)
        assertEquals(salon.id, notif.salonId)
        assertEquals(booking.id.value.toString(), notif.referenceId)
        assertEquals("BOOKING", notif.referenceType)
        assertTrue(notif.title.contains("نوبت جدید"))
        assertTrue(notif.message.contains(service.name))
    }

    @Test
    fun `successful booking cancellation generates exactly one BOOKING_CANCELLED notification`() {
        val booking = createUseCase.execute(
            CreateBookingCommand(salon.id, service.id, specialist.id, customer, startTime, null),
        )
        // One BOOKING_CREATED
        assertEquals(1L, notificationRepository.countUnreadBySalonId(salon.id))

        cancelUseCase.execute(CancelBookingCommand(booking.id, customer))

        // Total notifications now: 2 (1 CREATED, 1 CANCELLED)
        val notifications = notificationRepository.findBySalonId(salon.id, ai.rojan.backend.domain.common.PageRequest(0, 10)).content
        assertEquals(2, notifications.size)

        val cancelNotif = notifications.find { it.type == NotificationType.BOOKING_CANCELLED }
        org.junit.jupiter.api.Assertions.assertNotNull(cancelNotif)
        assertEquals(booking.id.value.toString(), cancelNotif!!.referenceId)
        assertTrue(cancelNotif.title.contains("لغو"))
    }

    @Test
    fun `confirming a pending booking generates exactly one BOOKING_CONFIRMED notification`() {
        val booking = createUseCase.execute(
            CreateBookingCommand(salon.id, service.id, specialist.id, customer, startTime, null),
        )

        confirmUseCase.execute(ConfirmBookingCommand(booking.id, owner))

        // Total notifications now: 2 (1 CREATED, 1 CONFIRMED)
        val notifications = notificationRepository.findBySalonId(salon.id, ai.rojan.backend.domain.common.PageRequest(0, 10)).content
        assertEquals(2, notifications.size)

        val confirmNotif = notifications.find { it.type == NotificationType.BOOKING_CONFIRMED }
        org.junit.jupiter.api.Assertions.assertNotNull(confirmNotif)
        assertEquals(booking.id.value.toString(), confirmNotif!!.referenceId)
        assertEquals("BOOKING", confirmNotif.referenceType)
        assertTrue(confirmNotif.title.contains("تأیید"))
    }

    @Test
    fun `completing a confirmed booking generates exactly one BOOKING_COMPLETED notification`() {
        val booking = createUseCase.execute(
            CreateBookingCommand(salon.id, service.id, specialist.id, customer, startTime, null),
        )
        confirmUseCase.execute(ConfirmBookingCommand(booking.id, owner))

        completeUseCase.execute(CompleteBookingCommand(booking.id, owner))

        // Total notifications now: 3 (1 CREATED, 1 CONFIRMED, 1 COMPLETED)
        val notifications = notificationRepository.findBySalonId(salon.id, ai.rojan.backend.domain.common.PageRequest(0, 10)).content
        assertEquals(3, notifications.size)

        val completeNotif = notifications.find { it.type == NotificationType.BOOKING_COMPLETED }
        org.junit.jupiter.api.Assertions.assertNotNull(completeNotif)
        assertEquals(booking.id.value.toString(), completeNotif!!.referenceId)
        assertTrue(completeNotif.title.contains("تکمیل"))
    }

    @Test
    fun `confirming a booking that is not PENDING throws and generates no new notification`() {
        val booking = createUseCase.execute(
            CreateBookingCommand(salon.id, service.id, specialist.id, customer, startTime, null),
        )
        confirmUseCase.execute(ConfirmBookingCommand(booking.id, owner)) // now CONFIRMED, not PENDING

        assertThrows<InvalidBookingStateException> {
            confirmUseCase.execute(ConfirmBookingCommand(booking.id, owner))
        }

        // Still exactly 2 (1 CREATED, 1 CONFIRMED) - the rejected second confirm never reached the
        // notification side effect, since the state-transition guard fires first.
        assertEquals(2, notificationRepository.findBySalonId(salon.id, ai.rojan.backend.domain.common.PageRequest(0, 10)).content.size)
    }

    @Test
    fun `completing a booking that is not CONFIRMED throws and generates no new notification`() {
        val booking = createUseCase.execute(
            CreateBookingCommand(salon.id, service.id, specialist.id, customer, startTime, null),
        )
        // Still PENDING - never confirmed.

        assertThrows<InvalidBookingStateException> {
            completeUseCase.execute(CompleteBookingCommand(booking.id, owner))
        }

        // Still exactly 1 (CREATED only) - the rejected complete never reached the notification
        // side effect.
        assertEquals(1, notificationRepository.findBySalonId(salon.id, ai.rojan.backend.domain.common.PageRequest(0, 10)).content.size)
    }

    @Test
    fun `confirming or completing without MANAGE_BOOKINGS permission throws and generates no notification`() {
        val booking = createUseCase.execute(
            CreateBookingCommand(salon.id, service.id, specialist.id, customer, startTime, null),
        )
        val stranger = UserId.new()

        assertThrows<SalonAccessDeniedException> {
            confirmUseCase.execute(ConfirmBookingCommand(booking.id, stranger))
        }
        assertThrows<SalonAccessDeniedException> {
            completeUseCase.execute(CompleteBookingCommand(booking.id, stranger))
        }

        // Still exactly 1 (CREATED only) - neither rejected call ever reached the notification side
        // effect, and the booking itself is still PENDING (confirm never actually ran).
        assertEquals(1, notificationRepository.findBySalonId(salon.id, ai.rojan.backend.domain.common.PageRequest(0, 10)).content.size)
    }
}
