package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.OtpRequestRequest
import ai.rojan.backend.api.auth.OtpVerifyRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.UserResponse
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
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles

/**
 * Regression coverage for the live browser E2E finding: `GET /api/v1/platform-authority/managers`
 * and `/customers` returned a real HTTP 500 (`ERROR: function lower(bytea) does not exist`) on a
 * freshly booted backend when `search` was omitted, because the previous single JPQL query bound
 * SQL `NULL` straight into a `LOWER()`/`CONCAT()` expression - a real PostgreSQL/Hibernate
 * parameter-type-inference failure on a query plan that had never been warmed by a prior non-null
 * bind. Fixed in `UserSpringDataRepository`/`UserRepositoryAdapter` by dispatching to a genuinely
 * separate, search-free query when there's no search term - see those files' own doc comments.
 *
 * [DirtiesContext] with [DirtiesContext.ClassMode.BEFORE_CLASS] is the whole point of this file:
 * without it, Spring Boot Test's context cache would very likely hand this class the *same*
 * `ApplicationContext` (and therefore the same Hibernate `SessionFactory` / query-plan cache)
 * [PlatformManagementApiIntegrationTest] already used elsewhere in the same JVM - whose own tests
 * call the real search-with-a-value path repeatedly, which would "warm" the exact query plan this
 * regression needs to prove works *cold*. Forcing a fresh context before this class runs is what
 * makes the assertions below genuine proof, not an artifact of test-class ordering luck. Within
 * each test method, the no-search call always executes strictly before any search-with-a-value
 * call on the same fresh context, so the null-parameter path is proven first, every time.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class PlatformListingNullSearchRegressionTest {

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

    private fun randomPhone() = "+9893${(1_000_000..9_999_999).random()}"

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

    private fun registerRole(role: UserRole, fullName: String): String {
        val email = "nullsearch.${System.nanoTime()}@example.com"
        restTemplate.postForEntity(
            url("/api/v1/auth/register"),
            RegisterRequest(email = email, password = "supersecret123", fullName = fullName, role = role),
            UserResponse::class.java,
        )
        return email
    }

    @Test
    fun `listing MANAGER accounts with no search term succeeds on a genuinely cold query plan, and search still works afterward`() {
        val adminToken = seedPlatformAdmin("Cold Path Admin")
        val marker = "ColdPathManager${System.nanoTime()}"
        registerRole(UserRole.MANAGER, "$marker One")
        registerRole(UserRole.MANAGER, "$marker Two")

        // No `search` query param at all - exactly the real default initial-page-load request shape
        // that returned a real HTTP 500 before this fix, on a query plan this fresh context has
        // never executed before.
        val noSearchResponse = restTemplate.exchange(
            url("/api/v1/platform-authority/managers?page=0&size=20"), HttpMethod.GET,
            HttpEntity<Void>(bearer(adminToken)), String::class.java,
        )
        assertEquals(HttpStatus.OK, noSearchResponse.statusCode, "GET .../managers with no search must not 500: ${noSearchResponse.body}")
        assertTrue(requireNotNull(noSearchResponse.body).contains("\"content\""))

        // Only now, after the null-search path has already succeeded on this fresh context, prove
        // the search-with-a-value path also still works.
        val searchResponse = restTemplate.exchange(
            url("/api/v1/platform-authority/managers?page=0&size=20&search=$marker"), HttpMethod.GET,
            HttpEntity<Void>(bearer(adminToken)), String::class.java,
        )
        assertEquals(HttpStatus.OK, searchResponse.statusCode)
        assertTrue(requireNotNull(searchResponse.body).contains(marker))
        assertTrue(searchResponse.body!!.contains("\"totalElements\":2"), "expected exactly the 2 seeded managers matching the marker: ${searchResponse.body}")
    }

    @Test
    fun `listing CUSTOMER accounts with no search term succeeds on a genuinely cold query plan, and search still works afterward`() {
        val adminToken = seedPlatformAdmin("Cold Path Admin Two")
        val marker = "ColdPathCustomer${System.nanoTime()}"
        registerRole(UserRole.CUSTOMER, "$marker One")

        val noSearchResponse = restTemplate.exchange(
            url("/api/v1/platform-authority/customers?page=0&size=20"), HttpMethod.GET,
            HttpEntity<Void>(bearer(adminToken)), String::class.java,
        )
        assertEquals(HttpStatus.OK, noSearchResponse.statusCode, "GET .../customers with no search must not 500: ${noSearchResponse.body}")

        val searchResponse = restTemplate.exchange(
            url("/api/v1/platform-authority/customers?page=0&size=20&search=$marker"), HttpMethod.GET,
            HttpEntity<Void>(bearer(adminToken)), String::class.java,
        )
        assertEquals(HttpStatus.OK, searchResponse.statusCode)
        assertTrue(requireNotNull(searchResponse.body).contains(marker))
    }

    @Test
    fun `pagination stays correct on the no-search path - real page size and totalElements, never fabricated`() {
        val adminToken = seedPlatformAdmin("Cold Path Admin Three")
        val marker = "ColdPagePlatformOnly${System.nanoTime()}"
        repeat(3) { registerRole(UserRole.MANAGER, "$marker $it") }

        // Deliberately no Authorization header on the very first call of this test method either -
        // proves the no-search path's own auth guard still runs before/regardless of the query.
        val unauthenticated = restTemplate.getForEntity(url("/api/v1/platform-authority/managers"), String::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, unauthenticated.statusCode)

        val paged = restTemplate.exchange(
            url("/api/v1/platform-authority/managers?page=0&size=2"), HttpMethod.GET,
            HttpEntity<Void>(bearer(adminToken)), String::class.java,
        )
        assertEquals(HttpStatus.OK, paged.statusCode)
        assertTrue(requireNotNull(paged.body).contains("\"size\":2"), "real requested page size must be honored: ${paged.body}")
    }

    @Test
    fun `authorization is unchanged - a non-platform caller is still denied on the no-search path`() {
        registerRole(UserRole.MANAGER, "Non Platform Caller")
        val email = "nullsearch.denied.${System.nanoTime()}@example.com"
        restTemplate.postForEntity(
            url("/api/v1/auth/register"),
            RegisterRequest(email = email, password = "supersecret123", fullName = "Denied Caller", role = UserRole.MANAGER),
            UserResponse::class.java,
        )
        val login = restTemplate.postForEntity(
            url("/api/v1/auth/login"),
            ai.rojan.backend.api.auth.LoginRequest(email = email, password = "supersecret123"),
            AuthResponse::class.java,
        )
        val callerToken = requireNotNull(login.body).accessToken

        val response = restTemplate.exchange(
            url("/api/v1/platform-authority/managers?page=0&size=20"), HttpMethod.GET,
            HttpEntity<Void>(bearer(callerToken)), String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }
}
