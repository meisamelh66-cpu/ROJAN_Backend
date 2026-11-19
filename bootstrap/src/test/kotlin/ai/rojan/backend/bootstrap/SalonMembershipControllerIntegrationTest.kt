package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.OtpRequestRequest
import ai.rojan.backend.api.auth.OtpVerifyRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.common.ApiError
import ai.rojan.backend.api.salon.AssignMembershipRequest
import ai.rojan.backend.api.salon.CreateSalonRequest
import ai.rojan.backend.api.salon.SalonMembershipResponse
import ai.rojan.backend.api.salon.SalonResponse
import ai.rojan.backend.api.salon.SetMembershipActiveRequest
import ai.rojan.backend.api.salon.StaffLookupResponse
import ai.rojan.backend.api.user.SalonAccessResponseDto
import ai.rojan.backend.domain.salon.SalonRole
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
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import java.util.regex.Pattern

/**
 * P0-3 Staff Access — real HTTP/real-DB coverage for [ai.rojan.backend.api.salon.SalonMembershipController],
 * which had no bootstrap-level integration test before this (only [ai.rojan.backend.application.salon.SalonMembershipUseCasesTest]
 * at the use-case layer, against in-memory fakes). Same convention as [PaymentControllerIntegrationTest]/
 * [OtpAuthenticationFlowIntegrationTest]: real [TestRestTemplate] HTTP against a real (embedded, no-Docker)
 * Postgres and real Spring Security - no MockMvc, no stubbed auth.
 *
 * Salon owners are registered via email+password (same as [PaymentControllerIntegrationTest]); the staff
 * candidate is registered via the real phone+OTP flow (same as [OtpAuthenticationFlowIntegrationTest]),
 * since [ai.rojan.backend.api.salon.SalonMembershipController.lookup] resolves accounts by phone number.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class SalonMembershipControllerIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

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

    private fun registerAndLoginOwner(): String {
        val email = "staff-access.${System.nanoTime()}@example.com"
        restTemplate.postForEntity(
            url("/api/v1/auth/register"),
            RegisterRequest(email = email, password = "supersecret123", fullName = "Owner $email", role = UserRole.MANAGER),
            UserResponse::class.java,
        )
        val login = restTemplate.postForEntity(
            url("/api/v1/auth/login"),
            LoginRequest(email = email, password = "supersecret123"),
            AuthResponse::class.java,
        )
        return requireNotNull(login.body).accessToken
    }

    private fun testPhoneNumber() = "+9891${(1_000_000..9_999_999).random()}"

    private val codeRegex = Pattern.compile("code is (\\d+)")

    private fun lastCodeSentTo(phoneNumber: String): String =
        logAppender.list
            .last { it.formattedMessage.contains(phoneNumber) }
            .formattedMessage
            .let { codeRegex.matcher(it).let { m -> m.find(); m.group(1) } }

    /** Registers a real ROJAN account (auto-registered as CUSTOMER by the OTP flow) findable by [phone]. */
    private fun registerStaffCandidate(phone: String, fullName: String): UUID {
        restTemplate.postForEntity(url("/api/v1/auth/otp/request"), OtpRequestRequest(phoneNumber = phone), String::class.java)
        val code = lastCodeSentTo(phone)
        val verify = restTemplate.postForEntity(
            url("/api/v1/auth/otp/verify"),
            OtpVerifyRequest(phoneNumber = phone, code = code, fullName = fullName),
            AuthResponse::class.java,
        )
        return requireNotNull(verify.body).user.id
    }

    private fun createSalon(ownerToken: String): UUID {
        val salon = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons"), HttpMethod.POST,
                HttpEntity(CreateSalonRequest("Staff Access Salon ${System.nanoTime()}", null, "09120000001", null, "Tehran"), bearer(ownerToken)),
                SalonResponse::class.java,
            ).body,
        )
        return salon.id
    }

    /**
     * `+` needs real percent-encoding (`%2B`) in a query string, or the server decodes it as a
     * space (the HTML form-encoding convention `@RequestParam` parsing follows) - confirmed by a
     * real 400 ("Invalid phone number:  98915864846", a literal leading space where `+` should
     * be). [java.net.URLEncoder] encodes correctly (`+` -> `%2B`, following that same
     * form-encoding convention); the result is wrapped in a plain [java.net.URI] - never handed
     * to [TestRestTemplate.exchange]'s `String` overload, which treats it as a URI *template* and
     * re-encodes it (double-encoding `%2B` into `%252B`). The single-arg `URI(String)` constructor
     * used here parses as-is, with no further encoding.
     */
    private fun lookupUrl(salonId: UUID, phone: String): java.net.URI =
        java.net.URI("${url("/api/v1/salons/$salonId/members/lookup")}?phone=${java.net.URLEncoder.encode(phone, "UTF-8")}")
    private fun memberUrl(salonId: UUID, userId: UUID) = url("/api/v1/salons/$salonId/members/$userId")
    private fun statusUrl(salonId: UUID, userId: UUID) = url("/api/v1/salons/$salonId/members/$userId/status")

    @Test
    fun `owner looks up a real staff candidate by phone and gets back the real identity fields`() {
        val ownerToken = registerAndLoginOwner()
        val salonId = createSalon(ownerToken)
        val phone = testPhoneNumber()
        val candidateId = registerStaffCandidate(phone, "Real Staff Candidate")

        val response = restTemplate.exchange(
            lookupUrl(salonId, phone), HttpMethod.GET, HttpEntity<Void>(bearer(ownerToken)), StaffLookupResponse::class.java,
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        val candidate = requireNotNull(response.body)
        assertEquals(candidateId, candidate.userId)
        assertEquals(phone, candidate.phoneNumber)
        assertEquals("Real Staff Candidate", candidate.fullName)
    }

    @Test
    fun `lookup for a phone number with no ROJAN account returns a real 404`() {
        val ownerToken = registerAndLoginOwner()
        val salonId = createSalon(ownerToken)

        val response = restTemplate.exchange(
            lookupUrl(salonId, testPhoneNumber()), HttpMethod.GET, HttpEntity<Void>(bearer(ownerToken)), ApiError::class.java,
        )

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `lookup with no bearer token at all is rejected with a real 401`() {
        val ownerToken = registerAndLoginOwner()
        val salonId = createSalon(ownerToken)

        // Same convention as AuthenticationFlowIntegrationTest's own 401 assertions: a request
        // rejected by Spring Security before reaching GlobalExceptionHandler is not guaranteed to
        // come back shaped like the app's own [ApiError], so this only asserts the status.
        val response = restTemplate.getForEntity(lookupUrl(salonId, testPhoneNumber()), String::class.java)

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `owner assigns a real RECEPTIONIST role to an existing account, and the membership is really active`() {
        val ownerToken = registerAndLoginOwner()
        val salonId = createSalon(ownerToken)
        val candidateId = registerStaffCandidate(testPhoneNumber(), "New Receptionist")

        val response = restTemplate.exchange(
            memberUrl(salonId, candidateId), HttpMethod.PUT,
            HttpEntity(AssignMembershipRequest(SalonRole.RECEPTIONIST), bearer(ownerToken)),
            SalonMembershipResponse::class.java,
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        val member = requireNotNull(response.body)
        assertEquals(candidateId, member.userId)
        assertEquals(salonId, member.salonId)
        assertEquals(SalonRole.RECEPTIONIST, member.role)
        assertTrue(member.active, "a newly assigned membership must be active")
    }

    @Test
    fun `a manager who does not own this salon is rejected with a real 403 - never allowed to assign a role here`() {
        val ownerToken = registerAndLoginOwner()
        val salonId = createSalon(ownerToken)
        val outsiderToken = registerAndLoginOwner() // a real MANAGER, but owns no salon here
        val candidateId = registerStaffCandidate(testPhoneNumber(), "Candidate")

        val response = restTemplate.exchange(
            memberUrl(salonId, candidateId), HttpMethod.PUT,
            HttpEntity(AssignMembershipRequest(SalonRole.RECEPTIONIST), bearer(outsiderToken)),
            ApiError::class.java,
        )

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `cross-tenant - Salon B's owner can never assign a role at Salon A, real 403`() {
        val ownerAToken = registerAndLoginOwner()
        val salonAId = createSalon(ownerAToken)
        val ownerBToken = registerAndLoginOwner()
        createSalon(ownerBToken) // Salon B exists but is irrelevant to this call
        val candidateId = registerStaffCandidate(testPhoneNumber(), "Candidate")

        val response = restTemplate.exchange(
            memberUrl(salonAId, candidateId), HttpMethod.PUT,
            HttpEntity(AssignMembershipRequest(SalonRole.MANAGER), bearer(ownerBToken)),
            ApiError::class.java,
        )

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `owner disables then re-enables a real membership, and the real active flag flips both times`() {
        val ownerToken = registerAndLoginOwner()
        val salonId = createSalon(ownerToken)
        val candidateId = registerStaffCandidate(testPhoneNumber(), "Candidate")
        restTemplate.exchange(
            memberUrl(salonId, candidateId), HttpMethod.PUT,
            HttpEntity(AssignMembershipRequest(SalonRole.RECEPTIONIST), bearer(ownerToken)),
            SalonMembershipResponse::class.java,
        )

        val disabled = restTemplate.exchange(
            statusUrl(salonId, candidateId), HttpMethod.PATCH,
            HttpEntity(SetMembershipActiveRequest(active = false), bearer(ownerToken)),
            SalonMembershipResponse::class.java,
        )
        assertEquals(HttpStatus.OK, disabled.statusCode)
        assertFalse(requireNotNull(disabled.body).active)

        val reEnabled = restTemplate.exchange(
            statusUrl(salonId, candidateId), HttpMethod.PATCH,
            HttpEntity(SetMembershipActiveRequest(active = true), bearer(ownerToken)),
            SalonMembershipResponse::class.java,
        )
        assertEquals(HttpStatus.OK, reEnabled.statusCode)
        assertTrue(requireNotNull(reEnabled.body).active)
    }

    @Test
    fun `end-to-end - disabling a membership over real HTTP really removes the member's own real salon-access permissions`() {
        val ownerToken = registerAndLoginOwner()
        val salonId = createSalon(ownerToken)
        val staffPhone = testPhoneNumber()
        val staffId = registerStaffCandidate(staffPhone, "Staff Member")
        restTemplate.exchange(
            memberUrl(salonId, staffId), HttpMethod.PUT,
            HttpEntity(AssignMembershipRequest(SalonRole.RECEPTIONIST), bearer(ownerToken)),
            SalonMembershipResponse::class.java,
        )
        val staffCode = lastCodeSentTo(staffPhone)
        // Re-authenticate as the staff member to get a fresh token for their own /salon-access read.
        restTemplate.postForEntity(url("/api/v1/auth/otp/request"), OtpRequestRequest(phoneNumber = staffPhone), String::class.java)
        val staffLogin = restTemplate.postForEntity(
            url("/api/v1/auth/otp/verify"),
            OtpVerifyRequest(phoneNumber = staffPhone, code = lastCodeSentTo(staffPhone), fullName = null),
            AuthResponse::class.java,
        )
        val staffToken = requireNotNull(staffLogin.body).accessToken

        restTemplate.exchange(
            statusUrl(salonId, staffId), HttpMethod.PATCH,
            HttpEntity(SetMembershipActiveRequest(active = false), bearer(ownerToken)),
            SalonMembershipResponse::class.java,
        )

        val access = restTemplate.exchange(
            url("/api/v1/users/me/salon-access"), HttpMethod.GET, HttpEntity<Void>(bearer(staffToken)), SalonAccessResponseDto::class.java,
        )
        assertEquals(HttpStatus.OK, access.statusCode)
        val entry = requireNotNull(access.body).memberships.first { it.salonId == salonId }
        assertFalse(entry.membershipActive, "a disabled membership must still be visible, with membershipActive=false")
        assertTrue(entry.permissions.isEmpty(), "a disabled membership must grant zero permissions")
        check(staffCode.isNotBlank())
    }
}
