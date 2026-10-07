package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.booking.BookingResponse
import ai.rojan.backend.api.booking.CreateBookingRequest
import ai.rojan.backend.api.booking.TimeSlotResponse
import ai.rojan.backend.api.common.PagedResponse
import ai.rojan.backend.api.notification.MarkAllReadResponse
import ai.rojan.backend.api.notification.NotificationResponse
import ai.rojan.backend.api.notification.UnreadCountResponse
import ai.rojan.backend.api.salon.CreateSalonRequest
import ai.rojan.backend.api.salon.CreateServiceCategoryRequest
import ai.rojan.backend.api.salon.CreateServiceRequest
import ai.rojan.backend.api.salon.CreateSpecialistRequest
import ai.rojan.backend.api.salon.SalonResponse
import ai.rojan.backend.api.salon.ServiceCategoryResponse
import ai.rojan.backend.api.salon.ServiceResponse
import ai.rojan.backend.api.salon.SpecialistResponse
import ai.rojan.backend.api.schedule.SetWeeklyAvailabilityRequest
import ai.rojan.backend.api.schedule.SetWorkingHoursRequest
import ai.rojan.backend.api.schedule.TimeIntervalDto
import ai.rojan.backend.domain.notification.Notification
import ai.rojan.backend.domain.notification.NotificationRepository
import ai.rojan.backend.domain.user.UserRole
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class SalonNotificationFlowIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @SpyBean
    private lateinit var notificationRepository: NotificationRepository

    private val restTemplate = TestRestTemplate()

    private fun url(path: String) = "http://localhost:$port$path"

    private fun bearer(token: String) = HttpHeaders().apply { setBearerAuth(token) }

    private fun registerAndLogin(role: UserRole): String {
        val email = "notif.${System.nanoTime()}@example.com"
        restTemplate.postForEntity(
            url("/api/v1/auth/register"),
            RegisterRequest(email = email, password = "supersecret123", fullName = "Test $role", role = role),
            UserResponse::class.java,
        )
        val response = restTemplate.postForEntity(
            url("/api/v1/auth/login"),
            LoginRequest(email = email, password = "supersecret123"),
            AuthResponse::class.java,
        )
        return requireNotNull(response.body).accessToken
    }

    private fun nextDayOfWeek(dayOfWeek: DayOfWeek): LocalDate {
        val base = LocalDate.now().plusDays(14)
        return base.plusDays(((dayOfWeek.value - base.dayOfWeek.value + 7) % 7).toLong())
    }

    private fun anyNotification(): ai.rojan.backend.domain.notification.Notification {
        Mockito.any(ai.rojan.backend.domain.notification.Notification::class.java)
        return ai.rojan.backend.domain.notification.Notification.create(
            salonId = ai.rojan.backend.domain.salon.SalonId(UUID.randomUUID()),
            type = ai.rojan.backend.domain.notification.NotificationType.BOOKING_CREATED,
            title = "",
            message = "",
        )
    }

    @Test
    fun `full notification flow - booking creation triggers notification, list, unread count, mark read, mark all read, cancel trigger, security`() {
        val managerToken = registerAndLogin(UserRole.MANAGER)
        val customerToken = registerAndLogin(UserRole.CUSTOMER)
        val outsiderToken = registerAndLogin(UserRole.MANAGER)

        // 1. Create salon
        val salon = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons"),
                HttpMethod.POST,
                HttpEntity(CreateSalonRequest("Glow Salon", null, "+1 555 0100", null, "1 Main St"), bearer(managerToken)),
                SalonResponse::class.java,
            ).body,
        )

        // 2. Category, Service, Specialist
        val category = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/categories"),
                HttpMethod.POST,
                HttpEntity(CreateServiceCategoryRequest("Hair", null), bearer(managerToken)),
                ServiceCategoryResponse::class.java,
            ).body,
        )

        val service = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/categories/${category.id}/services"),
                HttpMethod.POST,
                HttpEntity(CreateServiceRequest("Haircut", null, 30, BigDecimal("25.00")), bearer(managerToken)),
                ServiceResponse::class.java,
            ).body,
        )

        val specialist = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/specialists"),
                HttpMethod.POST,
                HttpEntity(CreateSpecialistRequest(null, "Jamie Stylist", null, null, "+989120000006", "Stylist"), bearer(managerToken)),
                SpecialistResponse::class.java,
            ).body,
        )

        val monday = nextDayOfWeek(DayOfWeek.MONDAY)

        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/working-hours/MONDAY"),
            HttpMethod.PUT,
            HttpEntity(SetWorkingHoursRequest(listOf(TimeIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)))), bearer(managerToken)),
            Unit::class.java,
        )

        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists/${specialist.id}/schedule/weekly-availability/MONDAY"),
            HttpMethod.PUT,
            HttpEntity(SetWeeklyAvailabilityRequest(listOf(TimeIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)))), bearer(managerToken)),
            Unit::class.java,
        )

        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/activate"),
            HttpMethod.POST,
            HttpEntity<Void>(bearer(managerToken)),
            SalonResponse::class.java,
        )

        // 3. Initial unread count should be 0
        val countBefore = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/notifications/unread-count"),
            HttpMethod.GET,
            HttpEntity<Unit>(bearer(managerToken)),
            UnreadCountResponse::class.java,
        )
        assertEquals(HttpStatus.OK, countBefore.statusCode)
        assertEquals(0L, countBefore.body!!.unreadCount)

        // 4. Slots lookup and create booking
        val slotsRes = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists/${specialist.id}/available-slots?serviceId=${service.id}&date=$monday&slotIntervalMinutes=30"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(customerToken)),
            Array<TimeSlotResponse>::class.java,
        )
        assertEquals(HttpStatus.OK, slotsRes.statusCode)
        val slots = requireNotNull(slotsRes.body).toList()
        assertTrue(slots.isNotEmpty())
        val chosenSlot = slots.first()

        val createBooking = restTemplate.exchange(
            url("/api/v1/bookings"),
            HttpMethod.POST,
            HttpEntity(CreateBookingRequest(salon.id, service.id, specialist.id, chosenSlot.start, "First visit"), bearer(customerToken)),
            BookingResponse::class.java,
        )
        assertEquals(HttpStatus.CREATED, createBooking.statusCode)
        val booking = requireNotNull(createBooking.body)

        // 5. Verify unread count is now 1
        val countAfter = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/notifications/unread-count"),
            HttpMethod.GET,
            HttpEntity<Unit>(bearer(managerToken)),
            UnreadCountResponse::class.java,
        )
        assertEquals(1L, countAfter.body!!.unreadCount)

        // 6. List notifications
        val pagedType = object : ParameterizedTypeReference<PagedResponse<NotificationResponse>>() {}
        val listRes = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/notifications?page=0&size=20"),
            HttpMethod.GET,
            HttpEntity<Unit>(bearer(managerToken)),
            pagedType,
        )
        assertEquals(HttpStatus.OK, listRes.statusCode)
        val notifications = listRes.body!!.content
        assertEquals(1, notifications.size)
        val createdNotif = notifications[0]
        assertEquals("BOOKING_CREATED", createdNotif.type)
        assertEquals(booking.id.toString(), createdNotif.referenceId)
        assertFalse(createdNotif.isRead)

        // 7. Mark single notification as read
        val markReadRes = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/notifications/${createdNotif.id}/read"),
            HttpMethod.PATCH,
            HttpEntity<Unit>(bearer(managerToken)),
            NotificationResponse::class.java,
        )
        assertEquals(HttpStatus.OK, markReadRes.statusCode)
        assertTrue(markReadRes.body!!.isRead)
        assertNotNull(markReadRes.body!!.readAt)
        assertNotNull(markReadRes.body!!.readBy)

        // Unread count should now be 0
        val countAfterRead = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/notifications/unread-count"),
            HttpMethod.GET,
            HttpEntity<Unit>(bearer(managerToken)),
            UnreadCountResponse::class.java,
        )
        assertEquals(0L, countAfterRead.body!!.unreadCount)

        // 8. Customer cancels booking -> triggers BOOKING_CANCELLED notification
        val cancelRes = restTemplate.exchange(
            url("/api/v1/bookings/${booking.id}/cancel"),
            HttpMethod.PATCH,
            HttpEntity<Unit>(bearer(customerToken)),
            BookingResponse::class.java,
        )
        assertEquals(HttpStatus.OK, cancelRes.statusCode)

        // Unread count should now be 1 again
        val countAfterCancel = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/notifications/unread-count"),
            HttpMethod.GET,
            HttpEntity<Unit>(bearer(managerToken)),
            UnreadCountResponse::class.java,
        )
        assertEquals(1L, countAfterCancel.body!!.unreadCount)

        // 9. Mark all read
        val markAllRes = restTemplate.postForEntity(
            url("/api/v1/salons/${salon.id}/notifications/mark-all-read"),
            HttpEntity<Unit>(bearer(managerToken)),
            MarkAllReadResponse::class.java,
        )
        assertEquals(HttpStatus.OK, markAllRes.statusCode)
        assertEquals(1, markAllRes.body!!.markedCount)

        // 10. Security: Unauthenticated request receives 401
        val unauthRes = restTemplate.getForEntity(
            url("/api/v1/salons/${salon.id}/notifications"),
            String::class.java,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, unauthRes.statusCode)

        // 11. Security: Outsider user receives 403
        val outsiderRes = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/notifications"),
            HttpMethod.GET,
            HttpEntity<Unit>(bearer(outsiderToken)),
            String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, outsiderRes.statusCode)

        // 12. Cross-tenant isolation: marking notification with wrong salonId receives 404
        val randomSalonId = UUID.randomUUID()
        val crossTenantRes = restTemplate.exchange(
            url("/api/v1/salons/$randomSalonId/notifications/${createdNotif.id}/read"),
            HttpMethod.PATCH,
            HttpEntity<Unit>(bearer(managerToken)),
            String::class.java,
        )
        assertEquals(HttpStatus.NOT_FOUND, crossTenantRes.statusCode)
    }

    @Test
    fun `atomic rollback - if notification persistence fails, booking reservation is rolled back and not persisted`() {
        val managerToken = registerAndLogin(UserRole.MANAGER)
        val customerToken = registerAndLogin(UserRole.CUSTOMER)

        val salon = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons"),
                HttpMethod.POST,
                HttpEntity(CreateSalonRequest("Rollback Salon", null, "+1 555 0199", null, "99 Main St"), bearer(managerToken)),
                SalonResponse::class.java,
            ).body,
        )
        val category = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/categories"),
                HttpMethod.POST,
                HttpEntity(CreateServiceCategoryRequest("Hair", null), bearer(managerToken)),
                ServiceCategoryResponse::class.java,
            ).body,
        )
        val service = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/categories/${category.id}/services"),
                HttpMethod.POST,
                HttpEntity(CreateServiceRequest("Haircut", null, 30, BigDecimal("25.00")), bearer(managerToken)),
                ServiceResponse::class.java,
            ).body,
        )
        val specialist = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/specialists"),
                HttpMethod.POST,
                HttpEntity(CreateSpecialistRequest(null, "Rollback Stylist", null, null, "+989120000099", "Stylist"), bearer(managerToken)),
                SpecialistResponse::class.java,
            ).body,
        )
        val tuesday = nextDayOfWeek(DayOfWeek.TUESDAY)
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/working-hours/TUESDAY"),
            HttpMethod.PUT,
            HttpEntity(SetWorkingHoursRequest(listOf(TimeIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)))), bearer(managerToken)),
            Unit::class.java,
        )
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists/${specialist.id}/schedule/weekly-availability/TUESDAY"),
            HttpMethod.PUT,
            HttpEntity(SetWeeklyAvailabilityRequest(listOf(TimeIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)))), bearer(managerToken)),
            Unit::class.java,
        )
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/activate"),
            HttpMethod.POST,
            HttpEntity<Void>(bearer(managerToken)),
            SalonResponse::class.java,
        )

        // Configure Spy to simulate notification persistence failure for this salon
        Mockito.doAnswer { invocation ->
            val notif = invocation.getArgument<Notification>(0)
            if (notif.salonId.value == salon.id) {
                throw RuntimeException("Simulated notification database write failure")
            }
            invocation.callRealMethod()
        }.`when`(notificationRepository).save(anyNotification())

        try {
            val bookingTime = tuesday.atTime(10, 0)
            val createBookingRes = restTemplate.exchange(
                url("/api/v1/bookings"),
                HttpMethod.POST,
                HttpEntity(CreateBookingRequest(salon.id, service.id, specialist.id, bookingTime, "Failing booking"), bearer(customerToken)),
                String::class.java,
            )
            // Call fails with 500 Internal Server Error due to notification failure
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, createBookingRes.statusCode)

            // Verify atomic rollback: booking must NOT exist in database
            val listBookingsRes = restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/bookings"),
                HttpMethod.GET,
                HttpEntity<Void>(bearer(managerToken)),
                object : ParameterizedTypeReference<PagedResponse<BookingResponse>>() {},
            )
            assertEquals(HttpStatus.OK, listBookingsRes.statusCode)
            val persistedBookings = requireNotNull(listBookingsRes.body).content
            assertTrue(persistedBookings.isEmpty(), "Booking reservation must be rolled back when notification persistence fails")

            // Verify unread notification count is 0
            val countRes = restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/notifications/unread-count"),
                HttpMethod.GET,
                HttpEntity<Unit>(bearer(managerToken)),
                UnreadCountResponse::class.java,
            )
            assertEquals(0L, countRes.body!!.unreadCount)
        } finally {
            Mockito.reset(notificationRepository)
        }
    }
}
