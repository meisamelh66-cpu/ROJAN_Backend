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
import java.time.LocalDateTime
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Proves the double-booking guard in [ai.rojan.backend.infrastructure.persistence.booking.BookingRepositoryAdapter.reserve]
 * actually serializes concurrent writers rather than just looking correct on
 * paper: many customers race to book the exact same specialist/time window
 * simultaneously against a real Postgres instance, and exactly one may win.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class BookingConflictConcurrencyIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private val restTemplate = TestRestTemplate()

    private fun url(path: String) = "http://localhost:$port$path"

    private fun bearer(token: String) = HttpHeaders().apply { setBearerAuth(token) }

    private fun registerAndLogin(role: UserRole): String {
        val email = "concurrency.${System.nanoTime()}.${Math.random()}@example.com"
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
    fun `exactly one of many concurrent overlapping booking requests for the same specialist succeeds`() {
        val managerToken = registerAndLogin(UserRole.MANAGER)

        val salon = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons"),
                HttpMethod.POST,
                HttpEntity(CreateSalonRequest("Concurrency Salon", null, "+1 555 0300", null, "3 Main St"), bearer(managerToken)),
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
                HttpEntity(CreateSpecialistRequest(null, "Contested Stylist", null, null), bearer(managerToken)),
                SpecialistResponse::class.java,
            ).body,
        )

        val startTime = LocalDateTime.now().plusDays(30).withHour(10).withMinute(0).withSecond(0).withNano(0)
        val threadCount = 12
        val customerTokens = (1..threadCount).map { registerAndLogin(UserRole.CUSTOMER) }

        val executor = Executors.newFixedThreadPool(threadCount)
        val readyLatch = CountDownLatch(threadCount)
        val startLatch = CountDownLatch(1)
        val statuses = Collections.synchronizedList(mutableListOf<Int>())

        val futures = customerTokens.map { token ->
            executor.submit {
                readyLatch.countDown()
                startLatch.await()
                val response = restTemplate.exchange(
                    url("/api/v1/bookings"),
                    HttpMethod.POST,
                    HttpEntity(
                        CreateBookingRequest(salon.id, service.id, specialist.id, startTime, null),
                        bearer(token),
                    ),
                    String::class.java,
                )
                statuses.add(response.statusCode.value())
            }
        }

        readyLatch.await(10, TimeUnit.SECONDS)
        startLatch.countDown()
        futures.forEach { it.get(60, TimeUnit.SECONDS) }
        executor.shutdown()

        assertEquals(threadCount, statuses.size)
        val successCount = statuses.count { it == HttpStatus.CREATED.value() }
        val conflictCount = statuses.count { it == HttpStatus.CONFLICT.value() }

        assertEquals(1, successCount, "expected exactly one booking to win the race, got statuses: $statuses")
        assertEquals(threadCount - 1, conflictCount, "every loser must see 409 Conflict, got statuses: $statuses")

        val salonBookings = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/bookings"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(managerToken)),
            Array<BookingResponse>::class.java,
        )
        val activeBookingsForSlot = requireNotNull(salonBookings.body).count { it.startTime == startTime }
        assertEquals(1, activeBookingsForSlot, "only one booking row should exist for the contested slot")
        assertTrue(activeBookingsForSlot > 0)
    }
}
