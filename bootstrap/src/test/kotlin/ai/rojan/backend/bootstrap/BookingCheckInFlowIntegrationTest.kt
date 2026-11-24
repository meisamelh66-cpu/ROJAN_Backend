package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.booking.BookingResponse
import ai.rojan.backend.api.booking.CreateBookingRequest
import ai.rojan.backend.api.salon.AssignMembershipRequest
import ai.rojan.backend.api.salon.CreateSalonRequest
import ai.rojan.backend.api.salon.CreateServiceCategoryRequest
import ai.rojan.backend.api.salon.CreateServiceRequest
import ai.rojan.backend.api.salon.CreateSpecialistRequest
import ai.rojan.backend.api.salon.SalonMembershipResponse
import ai.rojan.backend.api.salon.SalonResponse
import ai.rojan.backend.api.salon.ServiceCategoryResponse
import ai.rojan.backend.api.salon.ServiceResponse
import ai.rojan.backend.api.salon.SpecialistResponse
import ai.rojan.backend.api.schedule.SetWeeklyAvailabilityRequest
import ai.rojan.backend.api.schedule.SetWorkingHoursRequest
import ai.rojan.backend.api.schedule.TimeIntervalDto
import ai.rojan.backend.domain.booking.BookingStatus
import ai.rojan.backend.domain.salon.SalonRole
import ai.rojan.backend.domain.user.UserRole
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
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
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

/**
 * Reception Check-in (Phase 4): real HTTP-level verification of the new
 * `PATCH /api/v1/bookings/{bookingId}/check-in` endpoint, proving the authorization layer
 * directly - a RECEPTIONIST [ai.rojan.backend.domain.salon.SalonMembership] is allowed,
 * a plain customer is rejected - against a real (embedded, no-Docker) PostgreSQL and the
 * actual Spring Security filter chain, not a unit-level fake.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class BookingCheckInFlowIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private val restTemplate = TestRestTemplate()

    private fun url(path: String) = "http://localhost:$port$path"

    private fun bearer(token: String) = HttpHeaders().apply { setBearerAuth(token) }

    private fun registerAndLogin(role: UserRole): Pair<String, UUID> {
        val email = "checkin.${System.nanoTime()}@example.com"
        val registered = restTemplate.postForEntity(
            url("/api/v1/auth/register"),
            RegisterRequest(email = email, password = "supersecret123", fullName = "Test $role", role = role),
            UserResponse::class.java,
        )
        val login = restTemplate.postForEntity(
            url("/api/v1/auth/login"),
            LoginRequest(email = email, password = "supersecret123"),
            AuthResponse::class.java,
        )
        return requireNotNull(login.body).accessToken to requireNotNull(registered.body).id
    }

    private fun createSalon(ownerToken: String, name: String): SalonResponse = requireNotNull(
        restTemplate.exchange(
            url("/api/v1/salons"),
            HttpMethod.POST,
            HttpEntity(CreateSalonRequest(name, null, "+1 555 0100", null, "1 Main St"), bearer(ownerToken)),
            SalonResponse::class.java,
        ).body,
    )

    private fun createServiceAndSpecialist(ownerToken: String, salonId: UUID): Pair<ServiceResponse, SpecialistResponse> {
        val category = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/$salonId/categories"),
                HttpMethod.POST,
                HttpEntity(CreateServiceCategoryRequest("Hair", null), bearer(ownerToken)),
                ServiceCategoryResponse::class.java,
            ).body,
        )
        val service = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/$salonId/categories/${category.id}/services"),
                HttpMethod.POST,
                HttpEntity(CreateServiceRequest("Haircut", null, 30, BigDecimal("25.00")), bearer(ownerToken)),
                ServiceResponse::class.java,
            ).body,
        )
        val specialist = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/$salonId/specialists"),
                HttpMethod.POST,
                HttpEntity(CreateSpecialistRequest(null, "Jamie Stylist", null, null, "+989120000099", "Stylist"), bearer(ownerToken)),
                SpecialistResponse::class.java,
            ).body,
        )
        DayOfWeek.entries.forEach { day ->
            restTemplate.exchange(
                url("/api/v1/salons/$salonId/specialists/${specialist.id}/schedule/weekly-availability/$day"), HttpMethod.PUT,
                HttpEntity(SetWeeklyAvailabilityRequest(listOf(TimeIntervalDto(LocalTime.of(0, 0), LocalTime.of(23, 59)))), bearer(ownerToken)),
                String::class.java,
            )
        }
        return service to specialist
    }

    private fun activateSalon(ownerToken: String, salonId: UUID) {
        DayOfWeek.entries.forEach { day ->
            restTemplate.exchange(
                url("/api/v1/salons/$salonId/working-hours/$day"), HttpMethod.PUT,
                HttpEntity(SetWorkingHoursRequest(listOf(TimeIntervalDto(LocalTime.of(0, 0), LocalTime.of(23, 59)))), bearer(ownerToken)),
                String::class.java,
            )
        }
        restTemplate.exchange(url("/api/v1/salons/$salonId/activate"), HttpMethod.POST, HttpEntity<Void>(bearer(ownerToken)), SalonResponse::class.java)
    }

    private fun assignMembership(ownerToken: String, salonId: UUID, userId: UUID, role: SalonRole): SalonMembershipResponse =
        requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/$salonId/members/$userId"),
                HttpMethod.PUT,
                HttpEntity(AssignMembershipRequest(role), bearer(ownerToken)),
                SalonMembershipResponse::class.java,
            ).body,
        )

    private fun createAndConfirmBooking(customerToken: String, ownerToken: String, salonId: UUID, serviceId: UUID, specialistId: UUID): BookingResponse {
        val created = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/bookings"),
                HttpMethod.POST,
                HttpEntity(CreateBookingRequest(salonId, serviceId, specialistId, LocalDateTime.now().plusDays(1), null), bearer(customerToken)),
                BookingResponse::class.java,
            ).body,
        )
        return requireNotNull(
            restTemplate.exchange(
                url("/api/v1/bookings/${created.id}/confirm"),
                HttpMethod.PATCH,
                HttpEntity<Void>(bearer(ownerToken)),
                BookingResponse::class.java,
            ).body,
        )
    }

    @Test
    fun `a receptionist membership can check in a confirmed booking`() {
        val (ownerToken, _) = registerAndLogin(UserRole.MANAGER)
        val (customerToken, _) = registerAndLogin(UserRole.CUSTOMER)
        val (receptionistToken, receptionistId) = registerAndLogin(UserRole.CUSTOMER)
        val salon = createSalon(ownerToken, "Check-in Test Salon")
        val (service, specialist) = createServiceAndSpecialist(ownerToken, salon.id)
        activateSalon(ownerToken, salon.id)
        assignMembership(ownerToken, salon.id, receptionistId, SalonRole.RECEPTIONIST)
        val confirmed = createAndConfirmBooking(customerToken, ownerToken, salon.id, service.id, specialist.id)

        val response = restTemplate.exchange(
            url("/api/v1/bookings/${confirmed.id}/check-in"),
            HttpMethod.PATCH,
            HttpEntity<Void>(bearer(receptionistToken)),
            BookingResponse::class.java,
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(BookingStatus.CHECKED_IN, requireNotNull(response.body).status)
    }

    @Test
    fun `rejects check-in from a plain customer with no salon membership`() {
        val (ownerToken, _) = registerAndLogin(UserRole.MANAGER)
        val (customerToken, _) = registerAndLogin(UserRole.CUSTOMER)
        val salon = createSalon(ownerToken, "No Membership Salon")
        val (service, specialist) = createServiceAndSpecialist(ownerToken, salon.id)
        activateSalon(ownerToken, salon.id)
        val confirmed = createAndConfirmBooking(customerToken, ownerToken, salon.id, service.id, specialist.id)

        val response = restTemplate.exchange(
            url("/api/v1/bookings/${confirmed.id}/check-in"),
            HttpMethod.PATCH,
            HttpEntity<Void>(bearer(customerToken)),
            String::class.java,
        )

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `rejects check-in of a booking that is still pending`() {
        val (ownerToken, _) = registerAndLogin(UserRole.MANAGER)
        val (customerToken, _) = registerAndLogin(UserRole.CUSTOMER)
        val salon = createSalon(ownerToken, "Still Pending Salon")
        val (service, specialist) = createServiceAndSpecialist(ownerToken, salon.id)
        activateSalon(ownerToken, salon.id)
        val created = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/bookings"),
                HttpMethod.POST,
                HttpEntity(CreateBookingRequest(salon.id, service.id, specialist.id, LocalDateTime.now().plusDays(1), null), bearer(customerToken)),
                BookingResponse::class.java,
            ).body,
        )

        val response = restTemplate.exchange(
            url("/api/v1/bookings/${created.id}/check-in"),
            HttpMethod.PATCH,
            HttpEntity<Void>(bearer(ownerToken)),
            String::class.java,
        )

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
    }

    @Test
    fun `OpenAPI docs describe the check-in endpoint`() {
        val response = restTemplate.getForEntity(url("/v3/api-docs"), String::class.java)

        assertEquals(HttpStatus.OK, response.statusCode)
        val docs = requireNotNull(response.body)
        assertTrue(docs.contains("/api/v1/bookings/{bookingId}/check-in"))
    }
}
