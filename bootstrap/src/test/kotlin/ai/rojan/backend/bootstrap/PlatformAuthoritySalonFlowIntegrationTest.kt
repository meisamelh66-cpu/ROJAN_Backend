package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.OtpRequestRequest
import ai.rojan.backend.api.auth.OtpVerifyRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.common.PagedResponse
import ai.rojan.backend.api.publicsalon.PublicSalonListResponse
import ai.rojan.backend.api.salon.CreateSalonRequest
import ai.rojan.backend.api.salon.SalonResponse
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.salon.SalonOnboardingStatus
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
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.util.UriComponentsBuilder

/**
 * Admin Salon Visibility (the "missing third leg" of the Salon Onboarding/Activation lifecycle):
 * real HTTP + embedded Postgres, same pattern as every other file in this directory. Proves, end to
 * end, that [ai.rojan.backend.api.platformauthority.PlatformAuthoritySalonController]
 * (`GET /api/v1/platform-authority/salons`) is the one new, platform-role-gated way to see a DRAFT
 * salon, while `GET /api/v1/public/salons` (the real Customer/Marketplace contract,
 * [ai.rojan.backend.api.publicsalon.PublicSalonDirectoryController]) and
 * `GET /api/v1/salons` (the real authenticated Manager contract,
 * [ai.rojan.backend.api.salon.SalonController]) are both completely untouched and keep excluding it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class PlatformAuthoritySalonFlowIntegrationTest {

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

    /** Seeds a real, active PLATFORM_ADMIN directly - same approach [PlatformAuthorityFlowIntegrationTest] already uses, see its own doc comment for why. */
    private fun seedPlatformAdmin(fullName: String): String {
        val phone = randomPhone()
        userRepository.save(User.registerWithPhone(PhoneNumber(phone), fullName, UserRole.PLATFORM_ADMIN))
        return loginViaOtp(phone)
    }

    private fun seedPlatformReviewer(fullName: String): String {
        val phone = randomPhone()
        userRepository.save(User.registerWithPhone(PhoneNumber(phone), fullName, UserRole.PLATFORM_REVIEWER))
        return loginViaOtp(phone)
    }

    private fun registerAndLoginManager(fullName: String): String {
        val email = "platform-salon.${System.nanoTime()}@example.com"
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

    private fun createSalon(ownerToken: String, name: String): SalonResponse = requireNotNull(
        restTemplate.exchange(
            url("/api/v1/salons"), HttpMethod.POST,
            HttpEntity(CreateSalonRequest(name, null, "+1 555 0100", null, "1 Main St"), bearer(ownerToken)),
            SalonResponse::class.java,
        ).body,
    )

    private fun listPlatformSalons(token: String, name: String? = null, page: Int = 0, size: Int = 20): org.springframework.http.ResponseEntity<PagedResponse<SalonResponse>> {
        val uri = UriComponentsBuilder.fromHttpUrl(url("/api/v1/platform-authority/salons"))
            .queryParam("page", page)
            .queryParam("size", size)
            .apply { if (name != null) queryParam("name", name) }
            .build().toUri()
        return restTemplate.exchange(
            uri, HttpMethod.GET, HttpEntity<Void>(bearer(token)),
            object : org.springframework.core.ParameterizedTypeReference<PagedResponse<SalonResponse>>() {},
        )
    }

    private fun listPublicSalons(search: String? = null): List<PublicSalonListResponse> {
        val uri = UriComponentsBuilder.fromHttpUrl(url("/api/v1/public/salons"))
            .apply { if (search != null) queryParam("search", search) }
            .build().toUri()
        return requireNotNull(
            restTemplate.exchange(
                uri, HttpMethod.GET, HttpEntity<Void>(HttpHeaders()),
                object : org.springframework.core.ParameterizedTypeReference<PagedResponse<PublicSalonListResponse>>() {},
            ).body,
        ).content
    }

    @Test
    fun `PLATFORM_ADMIN can see a freshly created DRAFT salon via the new endpoint, with its real status`() {
        val adminToken = seedPlatformAdmin("Admin Salon One")
        val ownerToken = registerAndLoginManager("Owner One")
        val draft = createSalon(ownerToken, "Admin-Visible Draft Salon ${System.nanoTime()}")
        assertEquals(SalonOnboardingStatus.DRAFT, draft.onboardingStatus, "a freshly created salon must start DRAFT")

        val response = listPlatformSalons(adminToken, name = draft.name)

        assertEquals(HttpStatus.OK, response.statusCode)
        val found = response.body!!.content.find { it.id == draft.id }
        assertTrue(found != null, "PLATFORM_ADMIN must see the DRAFT salon via GET /api/v1/platform-authority/salons")
        assertEquals(SalonOnboardingStatus.DRAFT, found!!.onboardingStatus)
        assertTrue(found.active)
        assertEquals(draft.ownerId, found.ownerId)
        assertEquals(draft.slug, found.slug)
    }

    @Test
    fun `PLATFORM_REVIEWER can also list DRAFT salons - read access is shared with PLATFORM_ADMIN`() {
        val reviewerToken = seedPlatformReviewer("Reviewer Salon One")
        val ownerToken = registerAndLoginManager("Owner Two")
        val draft = createSalon(ownerToken, "Reviewer-Visible Draft Salon ${System.nanoTime()}")

        val response = listPlatformSalons(reviewerToken, name = draft.name)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(response.body!!.content.any { it.id == draft.id })
    }

    @Test
    fun `a normal authenticated MANAGER cannot access platform-wide salon listing`() {
        val ownerToken = registerAndLoginManager("Owner Three")
        createSalon(ownerToken, "Someone Else's Draft ${System.nanoTime()}")

        val response = restTemplate.exchange(
            url("/api/v1/platform-authority/salons"), HttpMethod.GET, HttpEntity<Void>(bearer(ownerToken)), String::class.java,
        )

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `a CUSTOMER cannot access platform-wide salon listing either`() {
        val customerToken = loginViaOtp(randomPhone())

        val response = restTemplate.exchange(
            url("/api/v1/platform-authority/salons"), HttpMethod.GET, HttpEntity<Void>(bearer(customerToken)), String::class.java,
        )

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `an unauthenticated caller cannot access platform-wide salon listing`() {
        val response = restTemplate.exchange(
            url("/api/v1/platform-authority/salons"), HttpMethod.GET, HttpEntity<Void>(HttpHeaders()), String::class.java,
        )

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `a DRAFT salon never appears via the real public discovery endpoint, even though PLATFORM_ADMIN can see it`() {
        val adminToken = seedPlatformAdmin("Admin Salon Two")
        val ownerToken = registerAndLoginManager("Owner Four")
        val uniqueName = "Still Draft Salon ${System.nanoTime()}"
        val draft = createSalon(ownerToken, uniqueName)

        val viaPlatformAuthority = listPlatformSalons(adminToken, name = uniqueName).body!!.content
        assertTrue(viaPlatformAuthority.any { it.id == draft.id }, "sanity check: the admin endpoint does see it")

        val viaPublicDirectory = listPublicSalons(search = uniqueName)
        assertFalse(viaPublicDirectory.any { it.id == draft.id }, "GET /api/v1/public/salons must never expose a DRAFT salon")
    }

    @Test
    fun `once activated, the real public discovery endpoint picks the salon up automatically - no backend change needed there`() {
        val ownerToken = registerAndLoginManager("Owner Five")
        val uniqueName = "Soon Active Salon ${System.nanoTime()}"
        val salon = createSalon(ownerToken, uniqueName)

        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/categories"), HttpMethod.POST,
            HttpEntity(ai.rojan.backend.api.salon.CreateServiceCategoryRequest("Hair", null), bearer(ownerToken)),
            ai.rojan.backend.api.salon.ServiceCategoryResponse::class.java,
        ).body!!.let { category ->
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/categories/${category.id}/services"), HttpMethod.POST,
                HttpEntity(
                    ai.rojan.backend.api.salon.CreateServiceRequest("Haircut", null, 30, java.math.BigDecimal("25.00")),
                    bearer(ownerToken),
                ),
                ai.rojan.backend.api.salon.ServiceResponse::class.java,
            )
        }
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists"), HttpMethod.POST,
            HttpEntity(
                ai.rojan.backend.api.salon.CreateSpecialistRequest(null, "Stylist One", null, null, "+989120000099", "Stylist"),
                bearer(ownerToken),
            ),
            ai.rojan.backend.api.salon.SpecialistResponse::class.java,
        )
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/working-hours/MONDAY"), HttpMethod.PUT,
            HttpEntity(
                ai.rojan.backend.api.schedule.SetWorkingHoursRequest(
                    listOf(ai.rojan.backend.api.schedule.TimeIntervalDto(java.time.LocalTime.of(9, 0), java.time.LocalTime.of(17, 0))),
                ),
                bearer(ownerToken),
            ),
            String::class.java,
        )

        // Still DRAFT, still absent from public discovery, right up until the explicit activate call.
        assertFalse(listPublicSalons(search = uniqueName).any { it.id == salon.id })

        val activation = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/activate"), HttpMethod.POST,
            HttpEntity<Void>(bearer(ownerToken)), SalonResponse::class.java,
        )
        assertEquals(HttpStatus.OK, activation.statusCode)
        assertEquals(SalonOnboardingStatus.ACTIVE, activation.body!!.onboardingStatus)

        val viaPublicDirectory = listPublicSalons(search = uniqueName)
        assertTrue(viaPublicDirectory.any { it.id == salon.id }, "an ACTIVE salon must be discoverable via the existing public endpoint, unchanged")
    }

    @Test
    fun `pagination and name filter both work on the new platform-wide salon listing`() {
        val adminToken = seedPlatformAdmin("Admin Salon Three")
        val ownerToken = registerAndLoginManager("Owner Six")
        val marker = System.nanoTime()
        val names = listOf("Zeta $marker A", "Zeta $marker B", "Zeta $marker C")
        names.forEach { createSalon(ownerToken, it) }

        val firstPage = listPlatformSalons(adminToken, name = "Zeta $marker", page = 0, size = 2).body!!
        val secondPage = listPlatformSalons(adminToken, name = "Zeta $marker", page = 1, size = 2).body!!

        assertEquals(3L, firstPage.totalElements)
        assertEquals(2, firstPage.content.size)
        assertEquals(1, secondPage.content.size)
        val allNames = (firstPage.content + secondPage.content).map { it.name }
        assertEquals(names.sorted(), allNames.sorted())
    }
}
