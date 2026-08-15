package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.booking.BookingResponse
import ai.rojan.backend.api.booking.CreateBookingRequest
import ai.rojan.backend.api.common.ApiError
import ai.rojan.backend.api.common.PagedResponse
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
import ai.rojan.backend.api.schedule.SetWorkingHoursRequest
import ai.rojan.backend.api.schedule.TimeIntervalDto
import ai.rojan.backend.domain.user.UserRole
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Verifies the API-hardening milestone's cross-cutting behavior: pagination
 * / filtering / sorting on browse endpoints, `Idempotency-Key` handling on
 * booking creation, and the reason-field redaction fix for non-owner
 * viewers of a specialist's schedule.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class ApiHardeningIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private val restTemplate = TestRestTemplate()

    private fun url(path: String) = "http://localhost:$port$path"

    private fun bearer(token: String) = HttpHeaders().apply { setBearerAuth(token) }

    private fun activateSalon(ownerToken: String, salonId: java.util.UUID) {
        restTemplate.exchange(
            url("/api/v1/salons/$salonId/working-hours/MONDAY"), HttpMethod.PUT,
            HttpEntity(SetWorkingHoursRequest(listOf(TimeIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)))), bearer(ownerToken)),
            String::class.java,
        )
        restTemplate.exchange(url("/api/v1/salons/$salonId/activate"), HttpMethod.POST, HttpEntity<Void>(bearer(ownerToken)), SalonResponse::class.java)
    }

    private fun registerAndLogin(role: UserRole): String {
        val email = "hardening.${System.nanoTime()}@example.com"
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

    @Test
    fun `browsing salons is paginated, name-filterable, and sortable`() {
        val ownerToken = registerAndLogin(UserRole.MANAGER)
        val suffix = System.nanoTime()
        val names = listOf("Aardvark Salon $suffix", "Bumblebee Salon $suffix", "Cactus Salon $suffix")
        names.forEach { name ->
            restTemplate.exchange(
                url("/api/v1/salons"),
                HttpMethod.POST,
                HttpEntity(CreateSalonRequest(name, null, "+1 555 0100", null, "1 Main St"), bearer(ownerToken)),
                SalonResponse::class.java,
            )
        }

        val firstPage = restTemplate.exchange(
            url("/api/v1/salons?page=0&size=2&name=${suffix}&sortDirection=asc"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)),
            object : ParameterizedTypeReference<PagedResponse<SalonResponse>>() {},
        )
        assertEquals(HttpStatus.OK, firstPage.statusCode)
        val page0 = requireNotNull(firstPage.body)
        assertEquals(2, page0.content.size)
        assertEquals(3, page0.totalElements)
        assertEquals(2, page0.totalPages)
        assertEquals(listOf(names[0], names[1]), page0.content.map { it.name })

        val secondPage = restTemplate.exchange(
            url("/api/v1/salons?page=1&size=2&name=${suffix}&sortDirection=asc"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)),
            object : ParameterizedTypeReference<PagedResponse<SalonResponse>>() {},
        )
        assertEquals(listOf(names[2]), requireNotNull(secondPage.body).content.map { it.name })

        val descending = restTemplate.exchange(
            url("/api/v1/salons?size=10&name=${suffix}&sortDirection=desc"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)),
            object : ParameterizedTypeReference<PagedResponse<SalonResponse>>() {},
        )
        assertEquals(names.reversed(), requireNotNull(descending.body).content.map { it.name })
    }

    @Test
    fun `pagination rejects an out-of-range page size`() {
        val ownerToken = registerAndLogin(UserRole.MANAGER)
        val response = restTemplate.exchange(
            url("/api/v1/salons?size=500"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)),
            ApiError::class.java,
        )
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertNotNull(requireNotNull(response.body).traceId)
    }

    @Test
    fun `a customer's bookings can be filtered by status`() {
        val managerToken = registerAndLogin(UserRole.MANAGER)
        val customerToken = registerAndLogin(UserRole.CUSTOMER)

        val salon = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons"),
                HttpMethod.POST,
                HttpEntity(CreateSalonRequest("Filter Test Salon", null, "+1 555 0600", null, "6 Main St"), bearer(managerToken)),
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
                HttpEntity(CreateSpecialistRequest(null, "Filter Stylist", null, null), bearer(managerToken)),
                SpecialistResponse::class.java,
            ).body,
        )
        activateSalon(managerToken, salon.id)

        val start = LocalDateTime.now().plusDays(60).withHour(9).withMinute(0).withSecond(0).withNano(0)
        val booking = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/bookings"),
                HttpMethod.POST,
                HttpEntity(CreateBookingRequest(salon.id, service.id, specialist.id, start, null), bearer(customerToken)),
                BookingResponse::class.java,
            ).body,
        )

        val pendingOnly = restTemplate.exchange(
            url("/api/v1/bookings/mine?status=pending"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(customerToken)),
            object : ParameterizedTypeReference<PagedResponse<BookingResponse>>() {},
        )
        assertTrue(requireNotNull(pendingOnly.body).content.any { it.id == booking.id })

        val confirmedOnly = restTemplate.exchange(
            url("/api/v1/bookings/mine?status=confirmed"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(customerToken)),
            object : ParameterizedTypeReference<PagedResponse<BookingResponse>>() {},
        )
        assertFalse(requireNotNull(confirmedOnly.body).content.any { it.id == booking.id })
    }

    @Test
    fun `replaying the same Idempotency-Key with the same payload returns the original booking, not a duplicate`() {
        val managerToken = registerAndLogin(UserRole.MANAGER)
        val customerToken = registerAndLogin(UserRole.CUSTOMER)
        val (salon, service, specialist) = createBookableSalon(managerToken)

        val start = LocalDateTime.now().plusDays(90).withHour(10).withMinute(0).withSecond(0).withNano(0)
        val request = CreateBookingRequest(salon.id, service.id, specialist.id, start, "idempotent request")
        val idempotencyKey = "test-key-${System.nanoTime()}"

        val headers = bearer(customerToken).apply { set("Idempotency-Key", idempotencyKey) }
        val first = restTemplate.exchange(
            url("/api/v1/bookings"), HttpMethod.POST, HttpEntity(request, headers), BookingResponse::class.java,
        )
        assertEquals(HttpStatus.CREATED, first.statusCode)

        val second = restTemplate.exchange(
            url("/api/v1/bookings"), HttpMethod.POST, HttpEntity(request, headers), BookingResponse::class.java,
        )
        assertEquals(HttpStatus.CREATED, second.statusCode)
        assertEquals(first.body?.id, second.body?.id)

        val salonBookings = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/bookings"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(managerToken)),
            object : ParameterizedTypeReference<PagedResponse<BookingResponse>>() {},
        )
        assertEquals(1, requireNotNull(salonBookings.body).content.count { it.startTime == start })
    }

    @Test
    fun `replaying the same Idempotency-Key with a different payload is rejected`() {
        val managerToken = registerAndLogin(UserRole.MANAGER)
        val customerToken = registerAndLogin(UserRole.CUSTOMER)
        val (salon, service, specialist) = createBookableSalon(managerToken)

        val idempotencyKey = "test-key-${System.nanoTime()}"
        val headers = bearer(customerToken).apply { set("Idempotency-Key", idempotencyKey) }

        val start1 = LocalDateTime.now().plusDays(91).withHour(9).withMinute(0).withSecond(0).withNano(0)
        restTemplate.exchange(
            url("/api/v1/bookings"),
            HttpMethod.POST,
            HttpEntity(CreateBookingRequest(salon.id, service.id, specialist.id, start1, null), headers),
            BookingResponse::class.java,
        )

        val start2 = start1.plusHours(3)
        val conflict = restTemplate.exchange(
            url("/api/v1/bookings"),
            HttpMethod.POST,
            HttpEntity(CreateBookingRequest(salon.id, service.id, specialist.id, start2, null), headers),
            ApiError::class.java,
        )
        assertEquals(HttpStatus.CONFLICT, conflict.statusCode)
    }

    @Test
    fun `a specialist leave reason is visible to the owner but redacted for other viewers`() {
        val managerToken = registerAndLogin(UserRole.MANAGER)
        val customerToken = registerAndLogin(UserRole.CUSTOMER)

        val salon = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons"),
                HttpMethod.POST,
                HttpEntity(CreateSalonRequest("Redaction Salon", null, "+1 555 0700", null, "7 Main St"), bearer(managerToken)),
                SalonResponse::class.java,
            ).body,
        )
        val specialist = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/specialists"),
                HttpMethod.POST,
                HttpEntity(CreateSpecialistRequest(null, "Private Stylist", null, null), bearer(managerToken)),
                SpecialistResponse::class.java,
            ).body,
        )
        val date = LocalDate.now().plusDays(10)
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists/${specialist.id}/schedule/leaves"),
            HttpMethod.POST,
            HttpEntity(CreateLeaveRequest(date, date, "Medical leave — confidential"), bearer(managerToken)),
            LeaveResponse::class.java,
        )

        val asOwner = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists/${specialist.id}/schedule/leaves"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(managerToken)),
            object : ParameterizedTypeReference<List<LeaveResponse>>() {},
        )
        assertEquals("Medical leave — confidential", requireNotNull(asOwner.body).first().reason)

        val asCustomer = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists/${specialist.id}/schedule/leaves"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(customerToken)),
            object : ParameterizedTypeReference<List<LeaveResponse>>() {},
        )
        assertNull(requireNotNull(asCustomer.body).first().reason)
    }

    @Test
    fun `a missing required query parameter returns the standard error shape, not a framework default page`() {
        val ownerToken = registerAndLogin(UserRole.MANAGER)
        val salon = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons"),
                HttpMethod.POST,
                HttpEntity(CreateSalonRequest("Malformed Request Salon", null, "+1 555 0800", null, "8 Main St"), bearer(ownerToken)),
                SalonResponse::class.java,
            ).body,
        )
        val specialist = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/specialists"),
                HttpMethod.POST,
                HttpEntity(CreateSpecialistRequest(null, "Stylist", null, null), bearer(ownerToken)),
                SpecialistResponse::class.java,
            ).body,
        )

        val response = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists/${specialist.id}/available-slots"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)),
            ApiError::class.java,
        )
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertNotNull(requireNotNull(response.body).traceId)
    }

    private data class BookableSalon(val salon: SalonResponse, val service: ServiceResponse, val specialist: SpecialistResponse)

    private fun createBookableSalon(managerToken: String): BookableSalon {
        val salon = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons"),
                HttpMethod.POST,
                HttpEntity(CreateSalonRequest("Idempotency Salon ${System.nanoTime()}", null, "+1 555 0900", null, "9 Main St"), bearer(managerToken)),
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
                HttpEntity(CreateSpecialistRequest(null, "Idempotent Stylist", null, null), bearer(managerToken)),
                SpecialistResponse::class.java,
            ).body,
        )
        activateSalon(managerToken, salon.id)
        return BookableSalon(salon, service, specialist)
    }
}
