package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.OtpRequestRequest
import ai.rojan.backend.api.auth.OtpVerifyRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.platformauthority.SuspendPlatformSalonRequest
import ai.rojan.backend.api.publicsalon.PublicSalonListResponse
import ai.rojan.backend.api.salon.CreatePublicSalonResponse
import ai.rojan.backend.api.salon.CreateSalonRequest
import ai.rojan.backend.api.salon.SalonResponse
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserRepository
import ai.rojan.backend.domain.user.UserRole
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

/**
 * Public Salon Onboarding (`POST /api/v1/salons/onboarding`) - the real end-user flow's
 * one-salon-per-account rule, against the real HTTP layer and embedded Postgres. Deliberately
 * never calls the generic `POST /api/v1/salons` (`SalonActivationFlowIntegrationTest` and many
 * others already cover that route's unrestricted, multi-salon-per-owner behavior, which this rule
 * must never touch).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class PublicSalonOnboardingFlowIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var userRepository: UserRepository

    private val restTemplate = TestRestTemplate()
    private val logAppender = ListAppender<ILoggingEvent>()
    private val smsLogger = LoggerFactory.getLogger("ai.rojan.backend.infrastructure.sms.LoggingSmsProvider") as Logger

    @BeforeEach
    fun attachLogAppender() {
        logAppender.start()
        smsLogger.addAppender(logAppender)
    }

    @AfterEach
    fun detachLogAppender() {
        smsLogger.detachAppender(logAppender)
        logAppender.stop()
    }

    private fun url(path: String) = "http://localhost:$port$path"

    private fun bearer(token: String) = HttpHeaders().apply { setBearerAuth(token) }

    private fun randomPhone() = "+9891${(1_000_000..9_999_999).random()}"

    private fun lastCodeSentTo(phoneNumber: String): String =
        logAppender.list
            .last { it.formattedMessage.contains(phoneNumber) }
            .formattedMessage
            .let { Regex("""code is (\d{4,8})""").find(it)!!.groupValues[1] }

    private fun loginViaOtp(phone: String): String {
        restTemplate.postForEntity(url("/api/v1/auth/otp/request"), OtpRequestRequest(phoneNumber = phone), String::class.java)
        val code = lastCodeSentTo(phone)
        val verify = restTemplate.postForEntity(
            url("/api/v1/auth/otp/verify"),
            OtpVerifyRequest(phoneNumber = phone, code = code, fullName = null),
            AuthResponse::class.java,
        )
        return requireNotNull(verify.body).accessToken
    }

    private fun seedPlatformAdmin(fullName: String): String {
        val phone = randomPhone()
        userRepository.save(User.registerWithPhone(PhoneNumber(phone), fullName, UserRole.PLATFORM_ADMIN))
        return loginViaOtp(phone)
    }

    private fun registerAndLogin(fullName: String): String {
        val email = "onboarding.${System.nanoTime()}@example.com"
        restTemplate.postForEntity(
            url("/api/v1/auth/register"),
            RegisterRequest(email = email, password = "supersecret123", fullName = fullName, role = UserRole.MANAGER),
            UserResponse::class.java,
        )
        val login = restTemplate.postForEntity(
            url("/api/v1/auth/login"),
            LoginRequest(email = email, password = "supersecret123"),
            AuthResponse::class.java,
        )
        return requireNotNull(login.body).accessToken
    }

    private fun onboardingCreate(token: String, name: String): ResponseEntity<CreatePublicSalonResponse> =
        restTemplate.exchange(
            url("/api/v1/salons/onboarding"),
            HttpMethod.POST,
            HttpEntity(CreateSalonRequest(name, null, "+1 555 0100", null, "1 Main St"), bearer(token)),
            CreatePublicSalonResponse::class.java,
        )

    @Test
    fun `a first-time caller gets a real, newly created salon with 201 and alreadyHasSalon=false`() {
        val token = registerAndLogin("Onboarding Owner One")

        val response = onboardingCreate(token, "Glow Salon ${System.nanoTime()}")

        assertEquals(HttpStatus.CREATED, response.statusCode)
        assertFalse(response.body!!.alreadyHasSalon)
    }

    @Test
    fun `a second onboarding attempt by the same account returns the existing salon with 200, creating nothing new`() {
        val token = registerAndLogin("Onboarding Owner Two")
        val first = onboardingCreate(token, "First Salon ${System.nanoTime()}")

        val second = onboardingCreate(token, "Second Salon Attempt ${System.nanoTime()}")

        assertEquals(HttpStatus.OK, second.statusCode)
        assertTrue(second.body!!.alreadyHasSalon)
        assertEquals(first.body!!.salon.id, second.body!!.salon.id)
        assertEquals(first.body!!.salon.name, second.body!!.salon.name, "the real existing salon is returned unchanged, never renamed to the second attempt's name")

        val mine = restTemplate.exchange(
            url("/api/v1/salons/mine"), HttpMethod.GET, HttpEntity<Void>(bearer(token)),
            object : org.springframework.core.ParameterizedTypeReference<List<SalonResponse>>() {},
        )
        assertEquals(1, mine.body!!.size, "exactly one salon must exist for this account - the real backend was never asked to create a second one")
    }

    @Test
    fun `two concurrent onboarding requests from the same account never both create a salon`() {
        val token = registerAndLogin("Onboarding Owner Three")
        val startLatch = CountDownLatch(1)
        val results = CopyOnWriteArrayList<ResponseEntity<CreatePublicSalonResponse>>()

        val threads = (1..2).map { i ->
            Thread {
                startLatch.await()
                results.add(onboardingCreate(token, "Concurrent Salon Attempt $i ${System.nanoTime()}"))
            }
        }
        threads.forEach { it.start() }
        startLatch.countDown()
        threads.forEach { it.join() }

        assertEquals(2, results.size)
        val createdCount = results.count { it.body!!.alreadyHasSalon.not() }
        val alreadyExistsCount = results.count { it.body!!.alreadyHasSalon }
        assertEquals(1, createdCount, "exactly one of the two concurrent requests must have actually created the salon")
        assertEquals(1, alreadyExistsCount, "the other must have been told a salon already exists")
        assertEquals(
            results[0].body!!.salon.id,
            results[1].body!!.salon.id,
            "both concurrent requests must end up pointing at the exact same single salon",
        )

        val mine = restTemplate.exchange(
            url("/api/v1/salons/mine"), HttpMethod.GET, HttpEntity<Void>(bearer(token)),
            object : org.springframework.core.ParameterizedTypeReference<List<SalonResponse>>() {},
        )
        assertEquals(1, mine.body!!.size, "the real backend must never end up with two salons for this account, even under real concurrency")
    }

    @Test
    fun `a different account is completely unaffected by another account already having a salon`() {
        val ownerToken = registerAndLogin("Onboarding Owner Four")
        onboardingCreate(ownerToken, "Owner Four's Salon ${System.nanoTime()}")

        val strangerToken = registerAndLogin("Onboarding Owner Five")
        val response = onboardingCreate(strangerToken, "Owner Five's Salon ${System.nanoTime()}")

        assertEquals(HttpStatus.CREATED, response.statusCode)
        assertFalse(response.body!!.alreadyHasSalon)
    }

    @Test
    fun `the generic unrestricted POST api v1 salons route is completely unaffected by this rule`() {
        val token = registerAndLogin("Onboarding Owner Six")
        onboardingCreate(token, "Onboarding-Created Salon ${System.nanoTime()}")

        // The real, pre-existing, unrestricted route - used by internal/admin/platform flows and
        // every other multi-salon integration test in this suite - must still freely create a
        // second salon for an account that already has one via the onboarding route.
        val secondViaGenericRoute = restTemplate.exchange(
            url("/api/v1/salons"),
            HttpMethod.POST,
            HttpEntity(CreateSalonRequest("Second Salon Via Generic Route ${System.nanoTime()}", null, "+1 555 0200", null, "2 Second St"), bearer(token)),
            SalonResponse::class.java,
        )

        assertEquals(HttpStatus.CREATED, secondViaGenericRoute.statusCode)

        val mine = restTemplate.exchange(
            url("/api/v1/salons/mine"), HttpMethod.GET, HttpEntity<Void>(bearer(token)),
            object : org.springframework.core.ParameterizedTypeReference<List<SalonResponse>>() {},
        )
        assertEquals(2, mine.body!!.size, "the generic route's own multi-salon behavior must be completely untouched by the onboarding rule")
    }

    private fun publicSalonNames(search: String): List<String> {
        val response = restTemplate.exchange(
            url("/api/v1/public/salons?search=${java.net.URLEncoder.encode(search, "UTF-8")}"),
            HttpMethod.GET,
            HttpEntity.EMPTY,
            object : org.springframework.core.ParameterizedTypeReference<ai.rojan.backend.api.common.PagedResponse<PublicSalonListResponse>>() {},
        )
        return requireNotNull(response.body).content.map { it.name }
    }

    @Test
    fun `immediate public visibility - a freshly onboarded salon is publicly discoverable right away, with no admin approval step`() {
        val token = registerAndLogin("Onboarding Owner Seven")
        val name = "Immediately Public Salon ${System.nanoTime()}"

        val response = onboardingCreate(token, name)

        assertEquals(HttpStatus.CREATED, response.statusCode)
        assertTrue(publicSalonNames(name).contains(name), "a brand-new salon must appear in real public discovery immediately after onboarding, with no separate activation/approval step")
    }

    @Test
    fun `immediate public visibility - platform admin can still suspend the freshly onboarded salon to hide it, and reinstate it to make it public again`() {
        val adminToken = seedPlatformAdmin("Admin Immediate Visibility One")
        val ownerToken = registerAndLogin("Onboarding Owner Eight")
        val name = "Suspendable Fresh Salon ${System.nanoTime()}"
        val created = onboardingCreate(ownerToken, name)
        val salonId = created.body!!.salon.id

        assertTrue(publicSalonNames(name).contains(name), "sanity check: the salon starts out publicly visible")

        restTemplate.exchange(
            url("/api/v1/platform-authority/salons/$salonId/suspend"), HttpMethod.POST,
            HttpEntity(SuspendPlatformSalonRequest("تکراری است"), bearer(adminToken)), String::class.java,
        )
        assertFalse(publicSalonNames(name).contains(name), "an admin-suspended salon must disappear from public discovery, exactly as for any other salon")

        restTemplate.exchange(
            url("/api/v1/platform-authority/salons/$salonId/reinstate"), HttpMethod.POST,
            HttpEntity<Void>(bearer(adminToken)), String::class.java,
        )
        assertTrue(publicSalonNames(name).contains(name), "reinstating must make the salon public again")
    }
}
