package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.booking.BookingResponse
import ai.rojan.backend.api.booking.CreateBookingRequest
import ai.rojan.backend.api.booking.RescheduleBookingRequest
import ai.rojan.backend.api.salon.CreateSalonRequest
import ai.rojan.backend.api.salon.CreateServiceCategoryRequest
import ai.rojan.backend.api.salon.CreateServiceRequest
import ai.rojan.backend.api.salon.CreateSpecialistRequest
import ai.rojan.backend.api.salon.SalonResponse
import ai.rojan.backend.api.salon.ServiceCategoryResponse
import ai.rojan.backend.api.salon.ServiceResponse
import ai.rojan.backend.api.salon.SpecialistResponse
import ai.rojan.backend.api.schedule.SetWorkingHoursRequest
import ai.rojan.backend.api.schedule.TimeIntervalDto
import ai.rojan.backend.domain.booking.BookingStatus
import ai.rojan.backend.domain.user.UserRole
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
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
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

private const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"

/**
 * H4 - proves the `Idempotency-Key` handling added to [ai.rojan.backend.api.booking.BookingController]'s
 * `confirm`/`cancel`/`complete`/`reschedule` behaves per `ADR-004_BOOKING_MUTATION_RELIABILITY`'s
 * Idempotency/Retry Safety properties, and specifically that the operation-aware fingerprint
 * (action + caller + booking, per that method's own doc comment) correctly tells apart a genuine
 * replay from a reused key applied to a different action - `create`'s own idempotency behavior has
 * no existing test coverage anywhere in this codebase, a gap this file does not attempt to close;
 * it covers only the four endpoints H4 added the behavior to.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class BookingIdempotencyIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private val restTemplate = TestRestTemplate()

    private fun url(path: String) = "http://localhost:$port$path"

    private fun bearer(token: String) = HttpHeaders().apply { setBearerAuth(token) }

    private fun bearerWithKey(token: String, idempotencyKey: String) =
        bearer(token).apply { set(IDEMPOTENCY_KEY_HEADER, idempotencyKey) }

    private fun registerAndLogin(role: UserRole): String {
        val email = "idempotency.${System.nanoTime()}.${Math.random()}@example.com"
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

    /** Owner registers, stands up an activated salon with one service/specialist/working-hours, and books a slot as a customer - returns (managerToken, customerToken, bookingId). */
    private fun setUpPendingBooking(): Triple<String, String, UUID> {
        val managerToken = registerAndLogin(UserRole.MANAGER)
        val salon = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons"), HttpMethod.POST,
                HttpEntity(CreateSalonRequest("Idempotency Salon", null, "+1 555 0400", null, "4 Main St"), bearer(managerToken)),
                SalonResponse::class.java,
            ).body,
        )
        val category = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/categories"), HttpMethod.POST,
                HttpEntity(CreateServiceCategoryRequest("Hair", null), bearer(managerToken)),
                ServiceCategoryResponse::class.java,
            ).body,
        )
        val service = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/categories/${category.id}/services"), HttpMethod.POST,
                HttpEntity(CreateServiceRequest("Haircut", null, 30, BigDecimal("25.00")), bearer(managerToken)),
                ServiceResponse::class.java,
            ).body,
        )
        val specialist = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/specialists"), HttpMethod.POST,
                HttpEntity(CreateSpecialistRequest(null, "Idempotency Stylist", null, null, "+989120000004", "Stylist"), bearer(managerToken)),
                SpecialistResponse::class.java,
            ).body,
        )
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/working-hours/MONDAY"), HttpMethod.PUT,
            HttpEntity(SetWorkingHoursRequest(listOf(TimeIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)))), bearer(managerToken)),
            String::class.java,
        )
        restTemplate.exchange(url("/api/v1/salons/${salon.id}/activate"), HttpMethod.POST, HttpEntity<Void>(bearer(managerToken)), SalonResponse::class.java)

        val customerToken = registerAndLogin(UserRole.CUSTOMER)
        val startTime = LocalDateTime.now().plusDays(30).withHour(10).withMinute(0).withSecond(0).withNano(0)
        val booking = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/bookings"), HttpMethod.POST,
                HttpEntity(CreateBookingRequest(salon.id, service.id, specialist.id, startTime, null), bearer(customerToken)),
                BookingResponse::class.java,
            ).body,
        )
        return Triple(managerToken, customerToken, booking.id)
    }

    // ---- Same request replay ----

    @Test
    fun `replaying the same Idempotency-Key on confirm returns the original response without re-confirming`() {
        val (managerToken, _, bookingId) = setUpPendingBooking()
        val key = "confirm-${UUID.randomUUID()}"

        val first = restTemplate.exchange(
            url("/api/v1/bookings/$bookingId/confirm"), HttpMethod.PATCH,
            HttpEntity<Void>(bearerWithKey(managerToken, key)), BookingResponse::class.java,
        )
        val second = restTemplate.exchange(
            url("/api/v1/bookings/$bookingId/confirm"), HttpMethod.PATCH,
            HttpEntity<Void>(bearerWithKey(managerToken, key)), BookingResponse::class.java,
        )

        assertEquals(HttpStatus.OK, first.statusCode)
        assertEquals(HttpStatus.OK, second.statusCode)
        assertEquals(requireNotNull(first.body).updatedAt, requireNotNull(second.body).updatedAt)
        assertEquals(BookingStatus.CONFIRMED, second.body!!.status)
    }

    // ---- Retry after success ----

    @Test
    fun `retrying reschedule with the same key and body after a successful move replays instead of moving again`() {
        val (_, customerToken, bookingId) = setUpPendingBooking()
        val newStart = LocalDateTime.now().plusDays(31).withHour(11).withMinute(0).withSecond(0).withNano(0)
        val key = "reschedule-${UUID.randomUUID()}"
        val body = RescheduleBookingRequest(newStart)

        val first = restTemplate.exchange(
            url("/api/v1/bookings/$bookingId/reschedule"), HttpMethod.PUT,
            HttpEntity(body, bearerWithKey(customerToken, key)), BookingResponse::class.java,
        )
        val retry = restTemplate.exchange(
            url("/api/v1/bookings/$bookingId/reschedule"), HttpMethod.PUT,
            HttpEntity(body, bearerWithKey(customerToken, key)), BookingResponse::class.java,
        )

        assertEquals(HttpStatus.OK, first.statusCode)
        assertEquals(HttpStatus.OK, retry.statusCode)
        assertEquals(newStart, requireNotNull(retry.body).startTime)
        assertEquals(first.body!!.updatedAt, retry.body!!.updatedAt)
    }

    // ---- Different actions with same booking ----

    @Test
    fun `reusing the same key for confirm then cancel on the same booking rejects the cancel and leaves the booking confirmed`() {
        val (managerToken, _, bookingId) = setUpPendingBooking()
        val reusedKey = "reused-${UUID.randomUUID()}"

        val confirm = restTemplate.exchange(
            url("/api/v1/bookings/$bookingId/confirm"), HttpMethod.PATCH,
            HttpEntity<Void>(bearerWithKey(managerToken, reusedKey)), BookingResponse::class.java,
        )
        assertEquals(HttpStatus.OK, confirm.statusCode)
        assertEquals(BookingStatus.CONFIRMED, confirm.body!!.status)

        val cancelAttempt = restTemplate.exchange(
            url("/api/v1/bookings/$bookingId/cancel"), HttpMethod.PATCH,
            HttpEntity<Void>(bearerWithKey(managerToken, reusedKey)), String::class.java,
        )
        assertEquals(HttpStatus.CONFLICT, cancelAttempt.statusCode)

        val stillConfirmed = restTemplate.exchange(
            url("/api/v1/bookings/$bookingId"), HttpMethod.GET,
            HttpEntity<Void>(bearer(managerToken)), BookingResponse::class.java,
        )
        assertEquals(BookingStatus.CONFIRMED, requireNotNull(stillConfirmed.body).status)
    }

    // ---- Idempotency conflict (same key, different body) ----

    @Test
    fun `replaying the same reschedule key with a different target time returns conflict and does not move the booking again`() {
        val (_, customerToken, bookingId) = setUpPendingBooking()
        val key = "reschedule-conflict-${UUID.randomUUID()}"
        val firstTarget = LocalDateTime.now().plusDays(32).withHour(9).withMinute(0).withSecond(0).withNano(0)
        val differentTarget = LocalDateTime.now().plusDays(33).withHour(14).withMinute(0).withSecond(0).withNano(0)

        val first = restTemplate.exchange(
            url("/api/v1/bookings/$bookingId/reschedule"), HttpMethod.PUT,
            HttpEntity(RescheduleBookingRequest(firstTarget), bearerWithKey(customerToken, key)), BookingResponse::class.java,
        )
        assertEquals(HttpStatus.OK, first.statusCode)

        val conflicting = restTemplate.exchange(
            url("/api/v1/bookings/$bookingId/reschedule"), HttpMethod.PUT,
            HttpEntity(RescheduleBookingRequest(differentTarget), bearerWithKey(customerToken, key)), String::class.java,
        )
        assertEquals(HttpStatus.CONFLICT, conflicting.statusCode)

        val unchanged = restTemplate.exchange(
            url("/api/v1/bookings/$bookingId"), HttpMethod.GET,
            HttpEntity<Void>(bearer(customerToken)), BookingResponse::class.java,
        )
        assertEquals(firstTarget, requireNotNull(unchanged.body).startTime)
    }

    // ---- No header: unchanged behavior (backward compatibility) ----

    @Test
    fun `omitting the Idempotency-Key header behaves exactly as before`() {
        val (managerToken, _, bookingId) = setUpPendingBooking()

        val confirmed = restTemplate.exchange(
            url("/api/v1/bookings/$bookingId/confirm"), HttpMethod.PATCH,
            HttpEntity<Void>(bearer(managerToken)), BookingResponse::class.java,
        )
        assertEquals(HttpStatus.OK, confirmed.statusCode)
        assertEquals(BookingStatus.CONFIRMED, requireNotNull(confirmed.body).status)

        // A second call with no key at all hits the use case's own state-machine guard directly,
        // same as before this change existed - not the idempotency layer.
        val secondConfirm = restTemplate.exchange(
            url("/api/v1/bookings/$bookingId/confirm"), HttpMethod.PATCH,
            HttpEntity<Void>(bearer(managerToken)), String::class.java,
        )
        assertEquals(HttpStatus.CONFLICT, secondConfirm.statusCode)
    }
}
