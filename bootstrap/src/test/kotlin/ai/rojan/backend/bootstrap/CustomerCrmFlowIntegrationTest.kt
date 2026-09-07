package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.booking.BookingResponse
import ai.rojan.backend.api.booking.CreateBookingRequest
import ai.rojan.backend.api.common.PagedResponse
import ai.rojan.backend.api.customer.AddCustomerNoteRequest
import ai.rojan.backend.api.customer.AddCustomerTagRequest
import ai.rojan.backend.api.customer.CreateCustomerRequest
import ai.rojan.backend.api.customer.CustomerNoteResponse
import ai.rojan.backend.api.customer.CustomerResponse
import ai.rojan.backend.api.customer.CustomerTagResponse
import ai.rojan.backend.api.customer.CustomerTimelineEntryResponse
import ai.rojan.backend.api.customer.UpdateCustomerRequest
import ai.rojan.backend.api.salon.CreateSalonRequest
import ai.rojan.backend.api.salon.CreateServiceCategoryRequest
import ai.rojan.backend.api.salon.CreateServiceRequest
import ai.rojan.backend.api.salon.CreateSpecialistRequest
import ai.rojan.backend.api.salon.SalonResponse
import ai.rojan.backend.api.salon.ServiceCategoryResponse
import ai.rojan.backend.api.salon.ServiceResponse
import ai.rojan.backend.api.salon.SpecialistResponse
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.customer.Customer
import ai.rojan.backend.domain.customer.CustomerRepository
import ai.rojan.backend.domain.customer.CustomerStatus
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
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * End-to-end verification of the Customer CRM vertical (Phase 1) against a
 * real (embedded, no-Docker) PostgreSQL and the actual HTTP layer -
 * profile/status/notes/tags/timeline/bookings, ownership authorization, and
 * cross-tenant isolation, mirroring `SalonManagementFlowIntegrationTest`'s
 * own shape. Does not touch, call, or assert against any Booking write
 * endpoint - "do not modify Booking Integration" - the one booking-related
 * assertion here is that a customer's bookings list is correctly empty for
 * an unlinked customer (see `GetCustomerBookingsUseCase`'s own doc comment).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class CustomerCrmFlowIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var customerRepository: CustomerRepository

    private val restTemplate = TestRestTemplate()

    private fun url(path: String) = "http://localhost:$port$path"

    private fun bearer(token: String) = HttpHeaders().apply { setBearerAuth(token) }

    private fun registerAndLogin(): String {
        val email = "crm.${System.nanoTime()}@example.com"
        restTemplate.postForEntity(
            url("/api/v1/auth/register"),
            RegisterRequest(email = email, password = "supersecret123", fullName = "Owner", role = UserRole.MANAGER),
            UserResponse::class.java,
        )
        val login = restTemplate.postForEntity(
            url("/api/v1/auth/login"),
            LoginRequest(email = email, password = "supersecret123"),
            AuthResponse::class.java,
        )
        return requireNotNull(login.body).accessToken
    }

    /** Like [registerAndLogin], but also returns the new account's id and allows a non-MANAGER role - needed only by the tenant-isolation test below, which registers a real CUSTOMER-role account to link a walk-in [Customer] record to. */
    private fun registerAndLoginWithId(role: UserRole): Pair<String, UUID> {
        val email = "crm.${System.nanoTime()}@example.com"
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

    private fun createSalon(token: String, name: String): SalonResponse = requireNotNull(
        restTemplate.exchange(
            url("/api/v1/salons"),
            HttpMethod.POST,
            HttpEntity(CreateSalonRequest(name, null, "+1 555 0100", null, "1 Main St"), bearer(token)),
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
    fun `full customer lifecycle - create, profile, status change, notes, tags, timeline`() {
        val ownerToken = registerAndLogin()
        val salon = createSalon(ownerToken, "Glow Salon")

        val createCustomer = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/customer-records"),
            HttpMethod.POST,
            HttpEntity(CreateCustomerRequest("Jane Doe", "+989123456789", null, null), bearer(ownerToken)),
            CustomerResponse::class.java,
        )
        assertEquals(HttpStatus.CREATED, createCustomer.statusCode)
        val customer = requireNotNull(createCustomer.body)
        assertEquals(CustomerStatus.LEAD, customer.status)
        assertEquals(0, BigDecimal.ZERO.compareTo(customer.lifetimeValue))
        assertTrue(customer.userId == null)

        val listCustomers = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/customer-records"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)),
            object : ParameterizedTypeReference<PagedResponse<CustomerResponse>>() {},
        )
        assertEquals(HttpStatus.OK, listCustomers.statusCode)
        assertTrue(listCustomers.body!!.content.any { it.id == customer.id })

        val statusChange = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/customer-records/${customer.id}"),
            HttpMethod.PATCH,
            HttpEntity(UpdateCustomerRequest(null, null, null, null, CustomerStatus.PROSPECT), bearer(ownerToken)),
            CustomerResponse::class.java,
        )
        assertEquals(HttpStatus.OK, statusChange.statusCode)
        assertEquals(CustomerStatus.PROSPECT, statusChange.body?.status)

        val addNote = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/customer-records/${customer.id}/notes"),
            HttpMethod.POST,
            HttpEntity(AddCustomerNoteRequest("Prefers morning appointments"), bearer(ownerToken)),
            CustomerNoteResponse::class.java,
        )
        assertEquals(HttpStatus.CREATED, addNote.statusCode)

        val addTag = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/customer-records/${customer.id}/tags"),
            HttpMethod.POST,
            HttpEntity(AddCustomerTagRequest("VIP"), bearer(ownerToken)),
            CustomerTagResponse::class.java,
        )
        assertEquals(HttpStatus.CREATED, addTag.statusCode)
        val tag = requireNotNull(addTag.body)

        val getWithTag = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/customer-records/${customer.id}"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)),
            CustomerResponse::class.java,
        )
        assertTrue(getWithTag.body!!.tags.contains("VIP"))

        val listNotes = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/customer-records/${customer.id}/notes"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)),
            object : ParameterizedTypeReference<List<CustomerNoteResponse>>() {},
        )
        assertEquals(HttpStatus.OK, listNotes.statusCode)
        assertTrue(listNotes.body!!.any { it.text == "Prefers morning appointments" })

        val listTags = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/customer-records/${customer.id}/tags"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)),
            object : ParameterizedTypeReference<List<CustomerTagResponse>>() {},
        )
        assertEquals(HttpStatus.OK, listTags.statusCode)
        assertTrue(listTags.body!!.any { it.id == tag.id && it.label == "VIP" })

        val timeline = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/customer-records/${customer.id}/timeline"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)),
            object : ParameterizedTypeReference<PagedResponse<CustomerTimelineEntryResponse>>() {},
        )
        assertEquals(HttpStatus.OK, timeline.statusCode)
        assertTrue(timeline.body!!.content.any { it.type == "NOTE" })
        assertTrue(timeline.body!!.content.any { it.type == "TAG_ADDED" })
        assertTrue(timeline.body!!.content.any { it.type == "STATUS_CHANGED" })

        val removeTag = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/customer-records/${customer.id}/tags/${tag.id}"),
            HttpMethod.DELETE,
            HttpEntity<Void>(bearer(ownerToken)),
            Void::class.java,
        )
        assertEquals(HttpStatus.NO_CONTENT, removeTag.statusCode)

        val bookings = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/customer-records/${customer.id}/bookings"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)),
            object : ParameterizedTypeReference<PagedResponse<Any>>() {},
        )
        assertEquals(HttpStatus.OK, bookings.statusCode)
        assertTrue(bookings.body!!.content.isEmpty()) // unlinked customer - see GetCustomerBookingsUseCase's own doc comment
    }

    @Test
    fun `rejects a duplicate phone number within the same salon`() {
        val ownerToken = registerAndLogin()
        val salon = createSalon(ownerToken, "Duplicate Test Salon")
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/customer-records"),
            HttpMethod.POST,
            HttpEntity(CreateCustomerRequest("Jane Doe", "+989111111111", null, null), bearer(ownerToken)),
            CustomerResponse::class.java,
        )

        val duplicate = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/customer-records"),
            HttpMethod.POST,
            HttpEntity(CreateCustomerRequest("Impersonator", "+989111111111", null, null), bearer(ownerToken)),
            String::class.java,
        )

        assertEquals(HttpStatus.CONFLICT, duplicate.statusCode)
        // BACKEND-CRM-RECOVERY-001: the historical `errorCode` field ("CUSTOMER_ALREADY_EXISTS")
        // was deliberately excluded from this recovery (out of scope, main's ApiError has no
        // such field) - the status-code assertion above is the actual behavior this test verifies.
    }

    @Test
    fun `rejects an illegal status transition`() {
        val ownerToken = registerAndLogin()
        val salon = createSalon(ownerToken, "Status Test Salon")
        val customer = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/customer-records"),
                HttpMethod.POST,
                HttpEntity(CreateCustomerRequest("Jane Doe", "+989222222222", null, null), bearer(ownerToken)),
                CustomerResponse::class.java,
            ).body,
        )

        val illegalJump = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/customer-records/${customer.id}"),
            HttpMethod.PATCH,
            HttpEntity(UpdateCustomerRequest(null, null, null, null, CustomerStatus.VIP), bearer(ownerToken)), // Lead -> Vip is illegal
            String::class.java,
        )

        assertEquals(HttpStatus.CONFLICT, illegalJump.statusCode)
        // BACKEND-CRM-RECOVERY-001: the historical `errorCode` field ("INVALID_CUSTOMER_STATE")
        // was deliberately excluded from this recovery (out of scope, main's ApiError has no
        // such field) - the status-code assertion above is the actual behavior this test verifies.
    }

    @Test
    fun `customer data never leaks between salons - cross-tenant get returns 404`() {
        val ownerToken = registerAndLogin()
        val salonA = createSalon(ownerToken, "Salon A")
        val salonB = createSalon(ownerToken, "Salon B")
        val customerOfA = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salonA.id}/customer-records"),
                HttpMethod.POST,
                HttpEntity(CreateCustomerRequest("Jane Doe", "+989333333333", null, null), bearer(ownerToken)),
                CustomerResponse::class.java,
            ).body,
        )

        val crossTenantGet = restTemplate.exchange(
            url("/api/v1/salons/${salonB.id}/customer-records/${customerOfA.id}"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)),
            String::class.java,
        )
        assertEquals(HttpStatus.NOT_FOUND, crossTenantGet.statusCode)

        val crossTenantNotes = restTemplate.exchange(
            url("/api/v1/salons/${salonB.id}/customer-records/${customerOfA.id}/notes"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)),
            String::class.java,
        )
        assertEquals(HttpStatus.NOT_FOUND, crossTenantNotes.statusCode)

        val crossTenantTags = restTemplate.exchange(
            url("/api/v1/salons/${salonB.id}/customer-records/${customerOfA.id}/tags"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)),
            String::class.java,
        )
        assertEquals(HttpStatus.NOT_FOUND, crossTenantTags.statusCode)
    }

    /**
     * P0 Security regression test for the tenant isolation fix in
     * `ROJAN_Customer_Booking_History_Tenant_Isolation_Fix_Report_v1.md`:
     * a customer linked to an account, who has also booked at a *different*
     * salon, must never have that other salon's booking (or its resulting
     * timeline event) appear in this salon's view of them. Uses the same
     * [linkedCustomer] repository bypass [ReceptionBookingFlowIntegrationTest]
     * already established, since linking has no public endpoint yet.
     */
    @Test
    fun `does not leak a linked customer's bookings or timeline from a different salon`() {
        val ownerToken = registerAndLogin()
        val (customerToken, customerUserId) = registerAndLoginWithId(UserRole.CUSTOMER)

        val salonA = createSalon(ownerToken, "Salon A")
        val salonB = createSalon(ownerToken, "Salon B")
        val (serviceA, specialistA) = createServiceAndSpecialist(ownerToken, salonA.id)
        val (serviceB, specialistB) = createServiceAndSpecialist(ownerToken, salonB.id)
        val customerOfA = linkedCustomer(salonA.id, customerUserId, "+989166000001")

        val bookingAtA = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/bookings"),
                HttpMethod.POST,
                HttpEntity(
                    CreateBookingRequest(salonA.id, serviceA.id, specialistA.id, LocalDateTime.now().plusDays(1), null),
                    bearer(customerToken),
                ),
                BookingResponse::class.java,
            ).body,
        )
        restTemplate.exchange(
            url("/api/v1/bookings"),
            HttpMethod.POST,
            HttpEntity(
                CreateBookingRequest(salonB.id, serviceB.id, specialistB.id, LocalDateTime.now().plusDays(2), null),
                bearer(customerToken),
            ),
            BookingResponse::class.java,
        )

        val bookingsOfA = restTemplate.exchange(
            url("/api/v1/salons/${salonA.id}/customer-records/${customerOfA.id.value}/bookings"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)),
            object : ParameterizedTypeReference<PagedResponse<BookingResponse>>() {},
        )
        assertEquals(HttpStatus.OK, bookingsOfA.statusCode)
        assertEquals(1, bookingsOfA.body!!.content.size)
        assertEquals(bookingAtA.id, bookingsOfA.body!!.content[0].id)

        val timelineOfA = restTemplate.exchange(
            url("/api/v1/salons/${salonA.id}/customer-records/${customerOfA.id.value}/timeline"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)),
            object : ParameterizedTypeReference<PagedResponse<CustomerTimelineEntryResponse>>() {},
        )
        assertEquals(HttpStatus.OK, timelineOfA.statusCode)
        assertEquals(1, timelineOfA.body!!.content.count { it.type == "BOOKING_CREATED" })
    }

    @Test
    fun `rejects a caller who does not own the salon`() {
        val ownerToken = registerAndLogin()
        val strangerToken = registerAndLogin()
        val salon = createSalon(ownerToken, "Private Salon")
        val customer = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/customer-records"),
                HttpMethod.POST,
                HttpEntity(CreateCustomerRequest("Jane Doe", "+989444444444", null, null), bearer(ownerToken)),
                CustomerResponse::class.java,
            ).body,
        )

        val strangerGet = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/customer-records/${customer.id}"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(strangerToken)),
            String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, strangerGet.statusCode)

        val strangerNotes = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/customer-records/${customer.id}/notes"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(strangerToken)),
            String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, strangerNotes.statusCode)

        val strangerTags = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/customer-records/${customer.id}/tags"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(strangerToken)),
            String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, strangerTags.statusCode)
    }

    @Test
    fun `rejects unauthenticated access`() {
        val response = restTemplate.postForEntity(
            url("/api/v1/salons/${java.util.UUID.randomUUID()}/customer-records"),
            CreateCustomerRequest("No Auth", "+989555555555", null, null),
            String::class.java,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `OpenAPI docs describe the customer CRM endpoints`() {
        val response = restTemplate.getForEntity(url("/v3/api-docs"), String::class.java)

        assertEquals(HttpStatus.OK, response.statusCode)
        val docs = requireNotNull(response.body)
        assertTrue(docs.contains("/api/v1/salons/{salonId}/customer-records"))
        assertTrue(docs.contains("/api/v1/salons/{salonId}/customer-records/{customerId}/timeline"))
        assertTrue(docs.contains("/api/v1/salons/{salonId}/customer-records/{customerId}/bookings"))
    }
}
