package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.booking.BookingResponse
import ai.rojan.backend.api.booking.CreateBookingRequest
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
import ai.rojan.backend.api.schedule.WeeklyAvailabilityResponse
import ai.rojan.backend.api.schedule.WorkingHoursResponse
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
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Manager Booking Creation Integrity follow-up. End-to-end verification
 * (real embedded Postgres, real HTTP layer, same pattern as
 * [BookingEngineFlowIntegrationTest]) of the two new capabilities this
 * follow-up adds: salon-scoped customer search
 * (`GET /salons/{salonId}/customers`) and creating a booking on behalf of
 * an existing customer (`CreateBookingRequest.customerId`) — plus the
 * authorization/validation rules acceptance criteria 1-3 and 8 require.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class ManagerBookingCreationIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private val restTemplate = TestRestTemplate()

    private fun url(path: String) = "http://localhost:$port$path"

    private fun bearer(token: String) = HttpHeaders().apply { setBearerAuth(token) }

    private fun registerAndLogin(role: UserRole, fullName: String = "Test $role"): Pair<String, UUID> {
        val email = "manager-booking.${System.nanoTime()}@example.com"
        restTemplate.postForEntity(
            url("/api/v1/auth/register"),
            RegisterRequest(email = email, password = "supersecret123", fullName = fullName, role = role),
            UserResponse::class.java,
        )
        val login = requireNotNull(
            restTemplate.postForEntity(
                url("/api/v1/auth/login"),
                LoginRequest(email = email, password = "supersecret123"),
                AuthResponse::class.java,
            ).body,
        )
        return login.accessToken to login.user.id
    }

    private fun nextDayOfWeek(dayOfWeek: DayOfWeek): LocalDate {
        val base = LocalDate.now().plusDays(14)
        return base.plusDays(((dayOfWeek.value - base.dayOfWeek.value + 7) % 7).toLong())
    }

    private class SalonSetup(
        val salon: SalonResponse,
        val service: ServiceResponse,
        val specialist: SpecialistResponse,
        val availableDate: LocalDate,
    )

    private fun setUpSalon(managerToken: String, salonName: String): SalonSetup {
        val salon = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons"),
                HttpMethod.POST,
                HttpEntity(CreateSalonRequest(salonName, null, "+1 555 0300", null, "3 Main St"), bearer(managerToken)),
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
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/working-hours/MONDAY"),
            HttpMethod.PUT,
            HttpEntity(SetWorkingHoursRequest(listOf(TimeIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)))), bearer(managerToken)),
            WorkingHoursResponse::class.java,
        )
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists/${specialist.id}/schedule/weekly-availability/MONDAY"),
            HttpMethod.PUT,
            HttpEntity(SetWeeklyAvailabilityRequest(listOf(TimeIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)))), bearer(managerToken)),
            WeeklyAvailabilityResponse::class.java,
        )
        return SalonSetup(salon, service, specialist, monday)
    }

    @Test
    fun `a manager can search and book only for a customer who has actually booked with their salon`() {
        val (managerToken, _) = registerAndLogin(UserRole.MANAGER)
        val (customerToken, customerId) = registerAndLogin(UserRole.CUSTOMER, fullName = "Alex Customer")
        val setup = setUpSalon(managerToken, "Search Test Salon")

        // Before any booking exists, the customer is not yet part of this salon's roster.
        val emptySearch = restTemplate.exchange(
            url("/api/v1/salons/${setup.salon.id}/customers"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(managerToken)),
            Array<UserResponse>::class.java,
        )
        assertEquals(HttpStatus.OK, emptySearch.statusCode)
        assertTrue(requireNotNull(emptySearch.body).none { it.id == customerId })

        // The customer books for themselves once - this is what makes them a "known customer" of this salon.
        val selfBooking = restTemplate.exchange(
            url("/api/v1/bookings"),
            HttpMethod.POST,
            HttpEntity(
                CreateBookingRequest(setup.salon.id, setup.service.id, setup.specialist.id, setup.availableDate.atTime(9, 0)),
                bearer(customerToken),
            ),
            BookingResponse::class.java,
        )
        assertEquals(HttpStatus.CREATED, selfBooking.statusCode)
        assertEquals(customerId, selfBooking.body?.customerId)

        // BACKEND-CRM-CUSTOMER-IDENTITY-001: the self-service booking also
        // anchored the customer to a first-class, linked CRM record for this
        // salon (auto-created from the account profile).
        val crmRecords = restTemplate.exchange(
            url("/api/v1/salons/${setup.salon.id}/customer-records"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(managerToken)),
            String::class.java,
        )
        assertEquals(HttpStatus.OK, crmRecords.statusCode)
        assertTrue(requireNotNull(crmRecords.body).contains("\"userId\":\"$customerId\""))
        assertTrue(requireNotNull(crmRecords.body).contains("Alex Customer"))

        // Now the salon owner's search finds them by a partial, case-insensitive name match.
        val search = restTemplate.exchange(
            url("/api/v1/salons/${setup.salon.id}/customers?query=alex"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(managerToken)),
            Array<UserResponse>::class.java,
        )
        assertEquals(HttpStatus.OK, search.statusCode)
        assertTrue(requireNotNull(search.body).any { it.id == customerId })

        // The manager creates a second booking on this customer's behalf.
        val managerCreated = restTemplate.exchange(
            url("/api/v1/bookings"),
            HttpMethod.POST,
            HttpEntity(
                CreateBookingRequest(
                    setup.salon.id,
                    setup.service.id,
                    setup.specialist.id,
                    setup.availableDate.atTime(10, 0),
                    customerId = customerId,
                ),
                bearer(managerToken),
            ),
            BookingResponse::class.java,
        )
        assertEquals(HttpStatus.CREATED, managerCreated.statusCode)
        val booking = requireNotNull(managerCreated.body)
        assertEquals(customerId, booking.customerId, "the booking must be attributed to the selected customer, not the manager")

        // It shows up in the salon's real booking list - the same endpoint Manager Calendar reads.
        val salonBookings = restTemplate.exchange(
            url("/api/v1/salons/${setup.salon.id}/bookings"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(managerToken)),
            String::class.java,
        )
        assertEquals(HttpStatus.OK, salonBookings.statusCode)
        assertTrue(requireNotNull(salonBookings.body).contains(booking.id.toString()))
    }

    @Test
    fun `a manager cannot book on behalf of a customer for a salon they do not own`() {
        val (ownerToken, _) = registerAndLogin(UserRole.MANAGER)
        val (otherManagerToken, _) = registerAndLogin(UserRole.MANAGER)
        val (customerToken, customerId) = registerAndLogin(UserRole.CUSTOMER)
        val setup = setUpSalon(ownerToken, "Owned By Someone Else Salon")

        restTemplate.exchange(
            url("/api/v1/bookings"),
            HttpMethod.POST,
            HttpEntity(
                CreateBookingRequest(setup.salon.id, setup.service.id, setup.specialist.id, setup.availableDate.atTime(9, 0)),
                bearer(customerToken),
            ),
            BookingResponse::class.java,
        )

        val forbidden = restTemplate.exchange(
            url("/api/v1/bookings"),
            HttpMethod.POST,
            HttpEntity(
                CreateBookingRequest(
                    setup.salon.id,
                    setup.service.id,
                    setup.specialist.id,
                    setup.availableDate.atTime(11, 0),
                    customerId = customerId,
                ),
                bearer(otherManagerToken),
            ),
            String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, forbidden.statusCode)

        val searchForbidden = restTemplate.exchange(
            url("/api/v1/salons/${setup.salon.id}/customers"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(otherManagerToken)),
            String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, searchForbidden.statusCode)
    }

    @Test
    fun `booking on behalf of a customer id that does not exist fails with 404, not a fabricated booking`() {
        val (managerToken, _) = registerAndLogin(UserRole.MANAGER)
        val setup = setUpSalon(managerToken, "Nonexistent Customer Salon")

        val response = restTemplate.exchange(
            url("/api/v1/bookings"),
            HttpMethod.POST,
            HttpEntity(
                CreateBookingRequest(
                    setup.salon.id,
                    setup.service.id,
                    setup.specialist.id,
                    setup.availableDate.atTime(9, 0),
                    customerId = UUID.randomUUID(),
                ),
                bearer(managerToken),
            ),
            String::class.java,
        )

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `existing customer self-booking (no customerId) still works unchanged`() {
        val (customerToken, customerId) = registerAndLogin(UserRole.CUSTOMER)
        val (managerToken, _) = registerAndLogin(UserRole.MANAGER)
        val setup = setUpSalon(managerToken, "Self Booking Still Works Salon")

        val response = restTemplate.exchange(
            url("/api/v1/bookings"),
            HttpMethod.POST,
            HttpEntity(
                CreateBookingRequest(setup.salon.id, setup.service.id, setup.specialist.id, setup.availableDate.atTime(9, 0)),
                bearer(customerToken),
            ),
            BookingResponse::class.java,
        )

        assertEquals(HttpStatus.CREATED, response.statusCode)
        assertEquals(customerId, response.body?.customerId)
    }
}
