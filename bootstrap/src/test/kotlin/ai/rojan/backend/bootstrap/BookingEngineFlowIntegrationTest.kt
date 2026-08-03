package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.booking.BookingResponse
import ai.rojan.backend.api.booking.CreateBookingRequest
import ai.rojan.backend.api.booking.RescheduleBookingRequest
import ai.rojan.backend.api.booking.TimeSlotResponse
import ai.rojan.backend.api.salon.CreateSalonRequest
import ai.rojan.backend.api.salon.CreateServiceCategoryRequest
import ai.rojan.backend.api.salon.CreateServiceRequest
import ai.rojan.backend.api.salon.CreateSpecialistRequest
import ai.rojan.backend.api.salon.SalonResponse
import ai.rojan.backend.api.salon.ServiceCategoryResponse
import ai.rojan.backend.api.salon.ServiceResponse
import ai.rojan.backend.api.salon.SpecialistResponse
import ai.rojan.backend.api.schedule.CreateLeaveRequest
import ai.rojan.backend.api.schedule.LeaveResponse
import ai.rojan.backend.api.schedule.SetWeeklyAvailabilityRequest
import ai.rojan.backend.api.schedule.SetWorkingHoursRequest
import ai.rojan.backend.api.schedule.TimeIntervalDto
import ai.rojan.backend.api.schedule.WeeklyAvailabilityResponse
import ai.rojan.backend.api.schedule.WorkingHoursResponse
import ai.rojan.backend.domain.booking.BookingStatus
import ai.rojan.backend.domain.user.UserRole
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * End-to-end verification of the booking-engine vertical: working hours,
 * specialist weekly availability, available-slot computation, the full
 * booking lifecycle (create/confirm/reschedule/cancel), and leave blocking
 * out availability — all against a real (embedded, no-Docker) PostgreSQL and
 * the actual HTTP layer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class BookingEngineFlowIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private val restTemplate = TestRestTemplate()

    private fun url(path: String) = "http://localhost:$port$path"

    private fun bearer(token: String) = HttpHeaders().apply { setBearerAuth(token) }

    private fun registerAndLogin(role: UserRole): String {
        val email = "booking.${System.nanoTime()}@example.com"
        restTemplate.postForEntity(
            url("/api/v1/auth/register"),
            RegisterRequest(email = email, password = "supersecret123", fullName = "Test $role", role = role),
            UserResponse::class.java,
        )
        val login = restTemplate.postForEntity(
            url("/api/v1/auth/login"),
            LoginRequest(email = email, password = "supersecret123"),
            AuthResponse::class.java,
        )
        return requireNotNull(login.body).accessToken
    }

    // The next occurrence of a given day of week, far enough out to never collide with "today" trimming.
    private fun nextDayOfWeek(dayOfWeek: DayOfWeek): LocalDate {
        val base = LocalDate.now().plusDays(14)
        return base.plusDays(((dayOfWeek.value - base.dayOfWeek.value + 7) % 7).toLong())
    }

    @Test
    fun `working hours, specialist availability, slots, and the full booking lifecycle work end-to-end`() {
        val managerToken = registerAndLogin(UserRole.MANAGER)
        val customerToken = registerAndLogin(UserRole.CUSTOMER)

        val salon = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons"),
                HttpMethod.POST,
                HttpEntity(CreateSalonRequest("Glow Salon", null, "+1 555 0100", null, "1 Main St"), bearer(managerToken)),
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
                HttpEntity(CreateSpecialistRequest(null, "Jamie Stylist", null, null), bearer(managerToken)),
                SpecialistResponse::class.java,
            ).body,
        )

        val monday = nextDayOfWeek(DayOfWeek.MONDAY)

        val workingHours = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/working-hours/MONDAY"),
            HttpMethod.PUT,
            HttpEntity(SetWorkingHoursRequest(listOf(TimeIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)))), bearer(managerToken)),
            WorkingHoursResponse::class.java,
        )
        assertEquals(HttpStatus.OK, workingHours.statusCode)

        val weeklyAvailability = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists/${specialist.id}/schedule/weekly-availability/MONDAY"),
            HttpMethod.PUT,
            HttpEntity(SetWeeklyAvailabilityRequest(listOf(TimeIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)))), bearer(managerToken)),
            WeeklyAvailabilityResponse::class.java,
        )
        assertEquals(HttpStatus.OK, weeklyAvailability.statusCode)

        val slotsBeforeBooking = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists/${specialist.id}/available-slots?serviceId=${service.id}&date=$monday&slotIntervalMinutes=30"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(customerToken)),
            Array<TimeSlotResponse>::class.java,
        )
        assertEquals(HttpStatus.OK, slotsBeforeBooking.statusCode)
        val slots = requireNotNull(slotsBeforeBooking.body).toList()
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
        assertEquals(BookingStatus.PENDING, booking.status)

        val slotsAfterBooking = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists/${specialist.id}/available-slots?serviceId=${service.id}&date=$monday&slotIntervalMinutes=30"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(customerToken)),
            Array<TimeSlotResponse>::class.java,
        )
        assertFalse(requireNotNull(slotsAfterBooking.body).any { it.start == chosenSlot.start })

        val confirmDenied = restTemplate.exchange(
            url("/api/v1/bookings/${booking.id}/confirm"),
            HttpMethod.PATCH,
            HttpEntity<Void>(bearer(customerToken)),
            String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, confirmDenied.statusCode)

        val confirmed = restTemplate.exchange(
            url("/api/v1/bookings/${booking.id}/confirm"),
            HttpMethod.PATCH,
            HttpEntity<Void>(bearer(managerToken)),
            BookingResponse::class.java,
        )
        assertEquals(HttpStatus.OK, confirmed.statusCode)
        assertEquals(BookingStatus.CONFIRMED, confirmed.body?.status)

        val newStart = chosenSlot.start.plusHours(2)
        val rescheduled = restTemplate.exchange(
            url("/api/v1/bookings/${booking.id}/reschedule"),
            HttpMethod.PUT,
            HttpEntity(RescheduleBookingRequest(newStart), bearer(customerToken)),
            BookingResponse::class.java,
        )
        assertEquals(HttpStatus.OK, rescheduled.statusCode)
        assertEquals(newStart, rescheduled.body?.startTime)

        val cancelled = restTemplate.exchange(
            url("/api/v1/bookings/${booking.id}/cancel"),
            HttpMethod.PATCH,
            HttpEntity<Void>(bearer(customerToken)),
            BookingResponse::class.java,
        )
        assertEquals(HttpStatus.OK, cancelled.statusCode)
        assertEquals(BookingStatus.CANCELLED, cancelled.body?.status)
    }

    @Test
    fun `a specialist on leave has no available slots for that date`() {
        val managerToken = registerAndLogin(UserRole.MANAGER)

        val salon = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons"),
                HttpMethod.POST,
                HttpEntity(CreateSalonRequest("Leave Test Salon", null, "+1 555 0200", null, "2 Main St"), bearer(managerToken)),
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
                HttpEntity(CreateSpecialistRequest(null, "On Leave Stylist", null, null), bearer(managerToken)),
                SpecialistResponse::class.java,
            ).body,
        )

        val tuesday = nextDayOfWeek(DayOfWeek.TUESDAY)

        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/working-hours/TUESDAY"),
            HttpMethod.PUT,
            HttpEntity(SetWorkingHoursRequest(listOf(TimeIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)))), bearer(managerToken)),
            WorkingHoursResponse::class.java,
        )
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists/${specialist.id}/schedule/weekly-availability/TUESDAY"),
            HttpMethod.PUT,
            HttpEntity(SetWeeklyAvailabilityRequest(listOf(TimeIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)))), bearer(managerToken)),
            WeeklyAvailabilityResponse::class.java,
        )
        val leave = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists/${specialist.id}/schedule/leaves"),
            HttpMethod.POST,
            HttpEntity(CreateLeaveRequest(tuesday, tuesday, "Vacation"), bearer(managerToken)),
            LeaveResponse::class.java,
        )
        assertEquals(HttpStatus.CREATED, leave.statusCode)

        val slots = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists/${specialist.id}/available-slots?serviceId=${service.id}&date=$tuesday&slotIntervalMinutes=30"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(managerToken)),
            Array<TimeSlotResponse>::class.java,
        )
        assertEquals(HttpStatus.OK, slots.statusCode)
        assertTrue(requireNotNull(slots.body).isEmpty())
    }

    @Test
    fun `OpenAPI docs describe the booking-engine endpoints`() {
        val response = restTemplate.getForEntity(url("/v3/api-docs"), String::class.java)

        assertEquals(HttpStatus.OK, response.statusCode)
        val docs = requireNotNull(response.body)
        assertTrue(docs.contains("/api/v1/salons/{salonId}/working-hours"))
        assertTrue(docs.contains("/api/v1/salons/{salonId}/specialists/{specialistId}/schedule/weekly-availability"))
        assertTrue(docs.contains("/api/v1/salons/{salonId}/specialists/{specialistId}/schedule/overrides"))
        assertTrue(docs.contains("/api/v1/salons/{salonId}/specialists/{specialistId}/schedule/leaves"))
        assertTrue(docs.contains("/api/v1/salons/{salonId}/specialists/{specialistId}/schedule/blocks"))
        assertTrue(docs.contains("/api/v1/salons/{salonId}/specialists/{specialistId}/available-slots"))
        assertTrue(docs.contains("/api/v1/bookings"))
        assertTrue(docs.contains("/api/v1/bookings/{bookingId}/confirm"))
        assertTrue(docs.contains("/api/v1/bookings/{bookingId}/cancel"))
        assertTrue(docs.contains("/api/v1/bookings/{bookingId}/reschedule"))
        assertTrue(docs.contains("/api/v1/salons/{salonId}/bookings"))
    }
}
