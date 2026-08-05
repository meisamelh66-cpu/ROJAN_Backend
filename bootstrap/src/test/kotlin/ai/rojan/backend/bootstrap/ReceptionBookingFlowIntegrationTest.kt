package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.booking.BookingResponse
import ai.rojan.backend.api.booking.CreateBookingForCustomerRequest
import ai.rojan.backend.api.customer.CreateCustomerRequest
import ai.rojan.backend.api.customer.CustomerResponse
import ai.rojan.backend.api.salon.CreateSalonRequest
import ai.rojan.backend.api.salon.CreateServiceCategoryRequest
import ai.rojan.backend.api.salon.CreateServiceRequest
import ai.rojan.backend.api.salon.CreateSpecialistRequest
import ai.rojan.backend.api.salon.SalonResponse
import ai.rojan.backend.api.salon.ServiceCategoryResponse
import ai.rojan.backend.api.salon.ServiceResponse
import ai.rojan.backend.api.salon.SpecialistResponse
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.booking.BookingStatus
import ai.rojan.backend.domain.customer.Customer
import ai.rojan.backend.domain.customer.CustomerRepository
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRole
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * ROJAN Reception Booking Flow (Phase 0). End-to-end verification of the
 * new owner-authorized `POST /api/v1/salons/{salonId}/bookings` against a
 * real (embedded, no-Docker) PostgreSQL and the actual HTTP layer -
 * happy path, the unlinked-customer rejection, non-owner rejection,
 * cross-salon tenant isolation, unauthenticated rejection, and OpenAPI
 * doc coverage. Does not touch, call, or assert against the customer
 * self-service `POST /api/v1/bookings` endpoint at all - it is unmodified,
 * already covered by `BookingEngineFlowIntegrationTest`.
 *
 * [customerRepository] is autowired directly for one reason only: linking a
 * `Customer` to a `User` has no public HTTP endpoint yet (identity
 * reconciliation is out of scope - see
 * `ROJAN_Customer_CRM_Implementation_Report_v1.md` §6.2 and
 * `ROJAN_Reception_Booking_Flow_Plan_v1.md` §4/§7). This is the one
 * necessary bypass to set up a *linked* customer for the happy-path test;
 * every actual assertion still goes through real HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class ReceptionBookingFlowIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var customerRepository: CustomerRepository

    private val restTemplate = TestRestTemplate()

    private fun url(path: String) = "http://localhost:$port$path"

    private fun bearer(token: String) = HttpHeaders().apply { setBearerAuth(token) }

    private fun registerAndLogin(role: UserRole): Pair<String, UUID> {
        val email = "reception.${System.nanoTime()}@example.com"
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
                HttpEntity(CreateSpecialistRequest(null, "Jamie Stylist", null, null), bearer(ownerToken)),
                SpecialistResponse::class.java,
            ).body,
        )
        return service to specialist
    }

    private fun linkedCustomer(salonId: UUID, userId: UUID, phone: String): Customer =
        customerRepository.save(
            Customer.create(SalonId(salonId), UserId(userId), "Linked Walk-in", PhoneNumber(phone), null, null),
        )

    @Test
    fun `creates a booking for a customer already linked to an account`() {
        val (ownerToken, _) = registerAndLogin(UserRole.MANAGER)
        val (_, customerUserId) = registerAndLogin(UserRole.CUSTOMER)
        val salon = createSalon(ownerToken, "Glow Salon")
        val (service, specialist) = createServiceAndSpecialist(ownerToken, salon.id)
        val customer = linkedCustomer(salon.id, customerUserId, "+989100000001")

        val response = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/bookings"),
            HttpMethod.POST,
            HttpEntity(
                CreateBookingForCustomerRequest(customer.id.value, service.id, specialist.id, LocalDateTime.now().plusDays(1), "Walk-in"),
                bearer(ownerToken),
            ),
            BookingResponse::class.java,
        )

        assertEquals(HttpStatus.CREATED, response.statusCode)
        val booking = requireNotNull(response.body)
        assertEquals(BookingStatus.PENDING, booking.status)
        assertEquals(customerUserId, booking.customerId)
        assertEquals(salon.id, booking.salonId)

        // Customer Timeline update - already automatic server-side (GetCustomerTimelineUseCase merges
        // booking events from Customer.userId), no separate write needed - see this class's own doc comment.
        val timeline = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/customers/${customer.id.value}/timeline"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)),
            String::class.java,
        )
        assertEquals(HttpStatus.OK, timeline.statusCode)
        assertTrue(requireNotNull(timeline.body).contains("BOOKING_CREATED"))
    }

    @Test
    fun `rejects a customer that has no linked account yet`() {
        val (ownerToken, _) = registerAndLogin(UserRole.MANAGER)
        val salon = createSalon(ownerToken, "Unlinked Test Salon")
        val (service, specialist) = createServiceAndSpecialist(ownerToken, salon.id)
        val walkIn = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/customers"),
                HttpMethod.POST,
                HttpEntity(CreateCustomerRequest("Walk-in Jane", "+989100000002", null, null), bearer(ownerToken)),
                CustomerResponse::class.java,
            ).body,
        )

        val response = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/bookings"),
            HttpMethod.POST,
            HttpEntity(
                CreateBookingForCustomerRequest(walkIn.id, service.id, specialist.id, LocalDateTime.now().plusDays(1), null),
                bearer(ownerToken),
            ),
            String::class.java,
        )

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertTrue(requireNotNull(response.body).contains("CUSTOMER_NOT_LINKED_TO_ACCOUNT"))
    }

    @Test
    fun `rejects a caller who is not the salon owner`() {
        val (ownerToken, _) = registerAndLogin(UserRole.MANAGER)
        val (strangerToken, _) = registerAndLogin(UserRole.MANAGER)
        val (_, customerUserId) = registerAndLogin(UserRole.CUSTOMER)
        val salon = createSalon(ownerToken, "Private Salon")
        val (service, specialist) = createServiceAndSpecialist(ownerToken, salon.id)
        val customer = linkedCustomer(salon.id, customerUserId, "+989100000003")

        val response = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/bookings"),
            HttpMethod.POST,
            HttpEntity(
                CreateBookingForCustomerRequest(customer.id.value, service.id, specialist.id, LocalDateTime.now().plusDays(1), null),
                bearer(strangerToken),
            ),
            String::class.java,
        )

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `rejects a customer that belongs to a different salon - tenant isolation`() {
        val (ownerToken, _) = registerAndLogin(UserRole.MANAGER)
        val (_, customerUserId) = registerAndLogin(UserRole.CUSTOMER)
        val salonA = createSalon(ownerToken, "Salon A")
        val salonB = createSalon(ownerToken, "Salon B")
        val (serviceB, specialistB) = createServiceAndSpecialist(ownerToken, salonB.id)
        val customerOfA = linkedCustomer(salonA.id, customerUserId, "+989100000004")

        val response = restTemplate.exchange(
            url("/api/v1/salons/${salonB.id}/bookings"),
            HttpMethod.POST,
            HttpEntity(
                CreateBookingForCustomerRequest(customerOfA.id.value, serviceB.id, specialistB.id, LocalDateTime.now().plusDays(1), null),
                bearer(ownerToken),
            ),
            String::class.java,
        )

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `rejects unauthenticated access`() {
        val response = restTemplate.postForEntity(
            url("/api/v1/salons/${UUID.randomUUID()}/bookings"),
            CreateBookingForCustomerRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), LocalDateTime.now().plusDays(1), null),
            String::class.java,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `OpenAPI docs describe the reception booking endpoint`() {
        val response = restTemplate.getForEntity(url("/v3/api-docs"), String::class.java)

        assertEquals(HttpStatus.OK, response.statusCode)
        val docs = requireNotNull(response.body)
        assertTrue(docs.contains("/api/v1/salons/{salonId}/bookings"))
    }
}
