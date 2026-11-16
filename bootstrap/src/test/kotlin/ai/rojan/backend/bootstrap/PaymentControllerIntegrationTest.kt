package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.booking.BookingResponse
import ai.rojan.backend.api.booking.CreateBookingRequest
import ai.rojan.backend.api.common.ApiError
import ai.rojan.backend.api.payment.InitiatePaymentRequest
import ai.rojan.backend.api.payment.InitiatePaymentResponse
import ai.rojan.backend.api.payment.PaymentTransactionResponse
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
import ai.rojan.backend.domain.payment.PaymentStatus
import ai.rojan.backend.domain.payment.PaymentTransactionMethod
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonMembershipRepository
import ai.rojan.backend.domain.salon.SalonRole
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRole
import ai.rojan.backend.infrastructure.payment.FakePaymentGatewayAdapter
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Payment Gateway Step 5 - the API/Controller layer wiring exactly the three Step 4 read/write use
 * cases to HTTP. Same convention as every other controller-level test in this project
 * ([BookingEngineFlowIntegrationTest] etc.): no `@WebMvcTest`/MockMvc anywhere in this codebase -
 * real HTTP via [TestRestTemplate] against a real (embedded, no-Docker) Postgres and real Spring
 * Security, with the real, test-profile [FakePaymentGatewayAdapter] standing in for Zarinpal.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class PaymentControllerIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var gateway: FakePaymentGatewayAdapter

    @Autowired
    private lateinit var salonMembershipRepository: SalonMembershipRepository

    private val restTemplate = TestRestTemplate()

    private fun url(path: String) = "http://localhost:$port$path"

    private fun bearer(token: String) = HttpHeaders().apply { setBearerAuth(token) }

    private fun registerAndLogin(role: UserRole): String {
        val email = "payment.${System.nanoTime()}@example.com"
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

    private fun nextDayOfWeek(dayOfWeek: DayOfWeek): LocalDate {
        val base = LocalDate.now().plusDays(14)
        return base.plusDays(((dayOfWeek.value - base.dayOfWeek.value + 7) % 7).toLong())
    }

    private data class SalonFixture(val ownerToken: String, val salonId: UUID, val serviceId: UUID, val specialistId: UUID, val servicePrice: BigDecimal)

    /** A fully activated salon with one bookable service, ready for a customer to book. */
    private fun createActivatedSalon(): SalonFixture {
        val ownerToken = registerAndLogin(UserRole.MANAGER)
        val salon = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons"), HttpMethod.POST,
                HttpEntity(CreateSalonRequest("Payment Salon ${System.nanoTime()}", null, "09120000000", null, "Tehran"), bearer(ownerToken)),
                SalonResponse::class.java,
            ).body,
        )
        val category = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/categories"), HttpMethod.POST,
                HttpEntity(CreateServiceCategoryRequest("Hair", null), bearer(ownerToken)),
                ServiceCategoryResponse::class.java,
            ).body,
        )
        val price = BigDecimal("150000")
        val service = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/categories/${category.id}/services"), HttpMethod.POST,
                HttpEntity(CreateServiceRequest("Haircut", null, 30, price), bearer(ownerToken)),
                ServiceResponse::class.java,
            ).body,
        )
        val specialist = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/specialists"), HttpMethod.POST,
                HttpEntity(CreateSpecialistRequest(null, "Stylist ${System.nanoTime()}", null, null, "+989120000099", "Stylist"), bearer(ownerToken)),
                SpecialistResponse::class.java,
            ).body,
        )
        val monday = nextDayOfWeek(DayOfWeek.MONDAY)
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/working-hours/MONDAY"), HttpMethod.PUT,
            HttpEntity(SetWorkingHoursRequest(listOf(TimeIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)))), bearer(ownerToken)),
            Any::class.java,
        )
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists/${specialist.id}/schedule/weekly-availability/MONDAY"), HttpMethod.PUT,
            HttpEntity(SetWeeklyAvailabilityRequest(listOf(TimeIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)))), bearer(ownerToken)),
            Any::class.java,
        )
        restTemplate.exchange(url("/api/v1/salons/${salon.id}/activate"), HttpMethod.POST, HttpEntity<Void>(bearer(ownerToken)), SalonResponse::class.java)
        return SalonFixture(ownerToken, salon.id, service.id, specialist.id, price)
    }

    private fun createPendingBooking(fixture: SalonFixture, customerToken: String, day: LocalDate = nextDayOfWeek(DayOfWeek.MONDAY), hour: Int = 10): BookingResponse {
        val request = CreateBookingRequest(
            salonId = fixture.salonId, serviceId = fixture.serviceId, specialistId = fixture.specialistId,
            startTime = day.atTime(hour, 0), notes = null,
        )
        return requireNotNull(
            restTemplate.exchange(url("/api/v1/bookings"), HttpMethod.POST, HttpEntity(request, bearer(customerToken)), BookingResponse::class.java).body,
        )
    }

    // ---------------- 1. successful initiate ----------------

    @Test
    fun `initiate succeeds for the booking's own customer and returns a full, consistent payment response`() {
        val fixture = createActivatedSalon()
        val customerToken = registerAndLogin(UserRole.CUSTOMER)
        val booking = createPendingBooking(fixture, customerToken)

        val response = restTemplate.exchange(
            url("/api/v1/payments"), HttpMethod.POST,
            HttpEntity(InitiatePaymentRequest(booking.id, PaymentTransactionMethod.ONLINE_GATEWAY, "key-${UUID.randomUUID()}"), bearer(customerToken)),
            InitiatePaymentResponse::class.java,
        )

        assertEquals(HttpStatus.CREATED, response.statusCode)
        val body = requireNotNull(response.body)
        assertEquals(PaymentStatus.AWAITING_CALLBACK, body.status)
        assertEquals(fixture.servicePrice.toLong(), body.amount)
        assertEquals("IRR", body.currency)
        assertEquals("FAKE", body.provider)
        assertTrue(body.authority.isNotBlank())
        assertTrue(body.redirectUrl.contains(body.authority))
    }

    // ---------------- 2. same-key replay ----------------

    @Test
    fun `replaying the same idempotency key for the same customer returns the identical transaction, not a duplicate`() {
        val fixture = createActivatedSalon()
        val customerToken = registerAndLogin(UserRole.CUSTOMER)
        val booking = createPendingBooking(fixture, customerToken)
        val key = "replay-${UUID.randomUUID()}"
        val request = InitiatePaymentRequest(booking.id, PaymentTransactionMethod.ONLINE_GATEWAY, key)

        val first = requireNotNull(
            restTemplate.exchange(url("/api/v1/payments"), HttpMethod.POST, HttpEntity(request, bearer(customerToken)), InitiatePaymentResponse::class.java).body,
        )
        val second = requireNotNull(
            restTemplate.exchange(url("/api/v1/payments"), HttpMethod.POST, HttpEntity(request, bearer(customerToken)), InitiatePaymentResponse::class.java).body,
        )

        assertEquals(first.transactionId, second.transactionId)
        assertEquals(first.authority, second.authority)
        assertEquals(first.redirectUrl, second.redirectUrl)
    }

    // ---------------- 3. different-customer-same-key denial ----------------

    @Test
    fun `a different customer presenting the same idempotency key is denied with 409, never given a fresh transaction`() {
        val fixture = createActivatedSalon()
        val customerA = registerAndLogin(UserRole.CUSTOMER)
        val customerB = registerAndLogin(UserRole.CUSTOMER)
        val bookingA = createPendingBooking(fixture, customerA, hour = 10)
        val bookingB = createPendingBooking(fixture, customerB, hour = 11)
        val sharedKey = "shared-${UUID.randomUUID()}"

        restTemplate.exchange(
            url("/api/v1/payments"), HttpMethod.POST,
            HttpEntity(InitiatePaymentRequest(bookingA.id, PaymentTransactionMethod.ONLINE_GATEWAY, sharedKey), bearer(customerA)),
            InitiatePaymentResponse::class.java,
        )
        val conflict = restTemplate.exchange(
            url("/api/v1/payments"), HttpMethod.POST,
            HttpEntity(InitiatePaymentRequest(bookingB.id, PaymentTransactionMethod.ONLINE_GATEWAY, sharedKey), bearer(customerB)),
            ApiError::class.java,
        )

        assertEquals(HttpStatus.CONFLICT, conflict.statusCode)
        assertEquals("IDEMPOTENCY_KEY_OWNED_BY_ANOTHER_CUSTOMER", requireNotNull(conflict.body).errorCode)
    }

    // ---------------- 4. callback success ----------------

    @Test
    fun `the zarinpal callback verifies with the gateway and moves a real transaction to SUCCEEDED`() {
        val fixture = createActivatedSalon()
        val customerToken = registerAndLogin(UserRole.CUSTOMER)
        val booking = createPendingBooking(fixture, customerToken)
        val initiation = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/payments"), HttpMethod.POST,
                HttpEntity(InitiatePaymentRequest(booking.id, PaymentTransactionMethod.ONLINE_GATEWAY, "cb-ok-${UUID.randomUUID()}"), bearer(customerToken)),
                InitiatePaymentResponse::class.java,
            ).body,
        )

        val callback = restTemplate.exchange(
            url("/api/v1/payments/callback/zarinpal?Authority=${initiation.authority}&Status=OK"),
            HttpMethod.GET, HttpEntity<Void>(HttpHeaders()), PaymentTransactionResponse::class.java,
        )

        assertEquals(HttpStatus.OK, callback.statusCode)
        assertEquals(PaymentStatus.SUCCEEDED, requireNotNull(callback.body).status)
        assertTrue(callback.body!!.referenceId!!.isNotBlank())
    }

    // ---------------- 5. callback failure ----------------

    @Test
    fun `the zarinpal callback moves to FAILED when the gateway itself reports verification failure`() {
        val fixture = createActivatedSalon()
        val customerToken = registerAndLogin(UserRole.CUSTOMER)
        val booking = createPendingBooking(fixture, customerToken)
        val initiation = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/payments"), HttpMethod.POST,
                HttpEntity(InitiatePaymentRequest(booking.id, PaymentTransactionMethod.ONLINE_GATEWAY, "cb-fail-${UUID.randomUUID()}"), bearer(customerToken)),
                InitiatePaymentResponse::class.java,
            ).body,
        )
        gateway.scriptVerificationFailure(initiation.authority, "card declined")

        val callback = restTemplate.exchange(
            url("/api/v1/payments/callback/zarinpal?Authority=${initiation.authority}&Status=NOK"),
            HttpMethod.GET, HttpEntity<Void>(HttpHeaders()), PaymentTransactionResponse::class.java,
        )

        assertEquals(HttpStatus.OK, callback.statusCode)
        assertEquals(PaymentStatus.FAILED, requireNotNull(callback.body).status)
        assertEquals("card declined", callback.body!!.failureReason)
    }

    // ---------------- 6. callback replay ----------------

    @Test
    fun `replaying the same callback authority is a safe no-op - exactly one real verify call, one terminal state`() {
        val fixture = createActivatedSalon()
        val customerToken = registerAndLogin(UserRole.CUSTOMER)
        val booking = createPendingBooking(fixture, customerToken)
        val initiation = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/payments"), HttpMethod.POST,
                HttpEntity(InitiatePaymentRequest(booking.id, PaymentTransactionMethod.ONLINE_GATEWAY, "cb-replay-${UUID.randomUUID()}"), bearer(customerToken)),
                InitiatePaymentResponse::class.java,
            ).body,
        )
        val verifyCallsBefore = gateway.verifyCallCount

        val firstCallback = restTemplate.exchange(
            url("/api/v1/payments/callback/zarinpal?Authority=${initiation.authority}&Status=OK"),
            HttpMethod.GET, HttpEntity<Void>(HttpHeaders()), PaymentTransactionResponse::class.java,
        )
        val secondCallback = restTemplate.exchange(
            url("/api/v1/payments/callback/zarinpal?Authority=${initiation.authority}&Status=OK"),
            HttpMethod.GET, HttpEntity<Void>(HttpHeaders()), PaymentTransactionResponse::class.java,
        )

        assertEquals(1, gateway.verifyCallCount - verifyCallsBefore)
        assertEquals(PaymentStatus.SUCCEEDED, requireNotNull(firstCallback.body).status)
        assertEquals(PaymentStatus.SUCCEEDED, requireNotNull(secondCallback.body).status)
    }

    // ---------------- 7 & 8. customer reads own / cannot read another's ----------------

    @Test
    fun `the paying customer can read their own payment, and a different customer is denied`() {
        val fixture = createActivatedSalon()
        val customerToken = registerAndLogin(UserRole.CUSTOMER)
        val otherCustomerToken = registerAndLogin(UserRole.CUSTOMER)
        val booking = createPendingBooking(fixture, customerToken)
        val initiation = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/payments"), HttpMethod.POST,
                HttpEntity(InitiatePaymentRequest(booking.id, PaymentTransactionMethod.ONLINE_GATEWAY, "get-own-${UUID.randomUUID()}"), bearer(customerToken)),
                InitiatePaymentResponse::class.java,
            ).body,
        )

        val own = restTemplate.exchange(
            url("/api/v1/payments/${initiation.transactionId}"), HttpMethod.GET, HttpEntity<Void>(bearer(customerToken)), PaymentTransactionResponse::class.java,
        )
        assertEquals(HttpStatus.OK, own.statusCode)

        val denied = restTemplate.exchange(
            url("/api/v1/payments/${initiation.transactionId}"), HttpMethod.GET, HttpEntity<Void>(bearer(otherCustomerToken)), ApiError::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, denied.statusCode)
    }

    // ---------------- 9. owner can read ----------------

    @Test
    fun `the salon owner can read a payment for their own salon`() {
        val fixture = createActivatedSalon()
        val customerToken = registerAndLogin(UserRole.CUSTOMER)
        val booking = createPendingBooking(fixture, customerToken)
        val initiation = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/payments"), HttpMethod.POST,
                HttpEntity(InitiatePaymentRequest(booking.id, PaymentTransactionMethod.ONLINE_GATEWAY, "owner-${UUID.randomUUID()}"), bearer(customerToken)),
                InitiatePaymentResponse::class.java,
            ).body,
        )

        val asOwner = restTemplate.exchange(
            url("/api/v1/payments/${initiation.transactionId}"), HttpMethod.GET, HttpEntity<Void>(bearer(fixture.ownerToken)), PaymentTransactionResponse::class.java,
        )
        assertEquals(HttpStatus.OK, asOwner.statusCode)
    }

    // ---------------- 10. manager (membership, VIEW_PAYMENTS) can read ----------------

    @Test
    fun `a salon member with the MANAGER role can read a payment for that salon`() {
        val fixture = createActivatedSalon()
        val customerToken = registerAndLogin(UserRole.CUSTOMER)
        val managerMemberToken = registerAndLogin(UserRole.MANAGER)
        val managerMemberId = currentUserIdOf(managerMemberToken)
        salonMembershipRepository.assign(SalonId(fixture.salonId), managerMemberId, SalonRole.MANAGER)
        val booking = createPendingBooking(fixture, customerToken)
        val initiation = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/payments"), HttpMethod.POST,
                HttpEntity(InitiatePaymentRequest(booking.id, PaymentTransactionMethod.ONLINE_GATEWAY, "manager-${UUID.randomUUID()}"), bearer(customerToken)),
                InitiatePaymentResponse::class.java,
            ).body,
        )

        val asManager = restTemplate.exchange(
            url("/api/v1/payments/${initiation.transactionId}"), HttpMethod.GET, HttpEntity<Void>(bearer(managerMemberToken)), PaymentTransactionResponse::class.java,
        )
        assertEquals(HttpStatus.OK, asManager.statusCode)
    }

    // ---------------- 11. cross-salon denial ----------------

    @Test
    fun `the owner of an unrelated salon is denied - salon-scoped access never leaks across salons`() {
        val fixture = createActivatedSalon()
        val otherSalonOwnerToken = createActivatedSalon().ownerToken
        val customerToken = registerAndLogin(UserRole.CUSTOMER)
        val booking = createPendingBooking(fixture, customerToken)
        val initiation = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/payments"), HttpMethod.POST,
                HttpEntity(InitiatePaymentRequest(booking.id, PaymentTransactionMethod.ONLINE_GATEWAY, "cross-salon-${UUID.randomUUID()}"), bearer(customerToken)),
                InitiatePaymentResponse::class.java,
            ).body,
        )

        val denied = restTemplate.exchange(
            url("/api/v1/payments/${initiation.transactionId}"), HttpMethod.GET, HttpEntity<Void>(bearer(otherSalonOwnerToken)), ApiError::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, denied.statusCode)
        assertEquals("ACCESS_DENIED", requireNotNull(denied.body).errorCode)
    }

    // ---------------- 12. invalid request validation ----------------

    @Test
    fun `an initiate request with a blank idempotency key is rejected with 400 before reaching the use case`() {
        val fixture = createActivatedSalon()
        val customerToken = registerAndLogin(UserRole.CUSTOMER)
        val booking = createPendingBooking(fixture, customerToken)

        val response = restTemplate.exchange(
            url("/api/v1/payments"), HttpMethod.POST,
            HttpEntity(InitiatePaymentRequest(booking.id, PaymentTransactionMethod.ONLINE_GATEWAY, ""), bearer(customerToken)),
            ApiError::class.java,
        )

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("VALIDATION_FAILED", requireNotNull(response.body).errorCode)
    }

    // ---------------- 13. unknown transaction ----------------

    @Test
    fun `reading an unknown transaction id returns 404 PAYMENT_TRANSACTION_NOT_FOUND`() {
        val customerToken = registerAndLogin(UserRole.CUSTOMER)

        val response = restTemplate.exchange(
            url("/api/v1/payments/${UUID.randomUUID()}"), HttpMethod.GET, HttpEntity<Void>(bearer(customerToken)), ApiError::class.java,
        )

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("PAYMENT_TRANSACTION_NOT_FOUND", requireNotNull(response.body).errorCode)
    }

    // ---------------- 14. application exception mapping (booking not eligible) ----------------

    @Test
    fun `initiating payment for a cancelled booking is rejected with 409 BOOKING_NOT_ELIGIBLE_FOR_PAYMENT`() {
        val fixture = createActivatedSalon()
        val customerToken = registerAndLogin(UserRole.CUSTOMER)
        val booking = createPendingBooking(fixture, customerToken)
        restTemplate.exchange(url("/api/v1/bookings/${booking.id}/cancel"), HttpMethod.PATCH, HttpEntity<Void>(bearer(customerToken)), BookingResponse::class.java)

        val response = restTemplate.exchange(
            url("/api/v1/payments"), HttpMethod.POST,
            HttpEntity(InitiatePaymentRequest(booking.id, PaymentTransactionMethod.ONLINE_GATEWAY, "not-eligible-${UUID.randomUUID()}"), bearer(customerToken)),
            ApiError::class.java,
        )

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("BOOKING_NOT_ELIGIBLE_FOR_PAYMENT", requireNotNull(response.body).errorCode)
    }

    @Test
    fun `a callback for an authority this backend never issued returns 404 UNKNOWN_PAYMENT_AUTHORITY`() {
        val response = restTemplate.exchange(
            url("/api/v1/payments/callback/zarinpal?Authority=NEVER-ISSUED-${UUID.randomUUID()}&Status=OK"),
            HttpMethod.GET, HttpEntity<Void>(HttpHeaders()), ApiError::class.java,
        )

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("UNKNOWN_PAYMENT_AUTHORITY", requireNotNull(response.body).errorCode)
    }

    @Test
    fun `the callback endpoint requires no bearer token at all`() {
        val response = restTemplate.exchange(
            url("/api/v1/payments/callback/zarinpal?Authority=NEVER-ISSUED-${UUID.randomUUID()}&Status=OK"),
            HttpMethod.GET, HttpEntity<Void>(HttpHeaders()), ApiError::class.java,
        )
        assertNotEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    private fun currentUserIdOf(token: String): UserId {
        val me = requireNotNull(
            restTemplate.exchange(url("/api/v1/users/me"), HttpMethod.GET, HttpEntity<Void>(bearer(token)), UserResponse::class.java).body,
        )
        return UserId(me.id)
    }
}
