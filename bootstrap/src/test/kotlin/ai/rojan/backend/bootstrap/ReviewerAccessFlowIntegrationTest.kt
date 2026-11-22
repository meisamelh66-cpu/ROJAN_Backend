package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.OtpRequestRequest
import ai.rojan.backend.api.auth.OtpVerifyRequest
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
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource

/**
 * Cafe Bazaar reviewer access — real end-to-end, against a real (embedded)
 * Postgres and the actual HTTP layer, exactly like [OtpAuthenticationFlowIntegrationTest]
 * (which this file deliberately does not modify or extend — a fully separate
 * file so the existing real-OTP integration coverage is provably untouched).
 *
 * Every test here calls the one real entry point the real Manager app would
 * use: `POST /api/v1/auth/otp/verify`. There is no second endpoint for the
 * reviewer path to go through - per the approved design, Option A.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
// The shared `test` profile deliberately loosens rojan.security.otp.verify-limit-per-phone-window
// to 10000 (application-test.yml) so unrelated test volume never trips it — that makes it
// impossible to deterministically prove a 429 within a reasonable number of real HTTP calls.
// This override is scoped to ONLY this test class's own Spring context (TestPropertySource,
// not a change to application-test.yml or any shared config file) — no other test class's
// rate-limit behavior is affected. verify-window-seconds is left exactly as the shared profile
// already has it (inherited from application.yml's own default, 600s) — only the attempt
// *count* is tightened here, not the time semantics, mirroring the same "volume vs. time
// semantics" split application-test.yml's own comment already draws for the OTP suite.
@TestPropertySource(properties = ["rojan.security.otp.verify-limit-per-phone-window=3"])
class ReviewerAccessFlowIntegrationTest {

    /** Must match the @TestPropertySource override above exactly. */
    private val configuredVerifyLimit = 3

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

    private fun randomPhone() = "+9894${(1_000_000..9_999_999).random()}"

    private fun lastCodeSentTo(phoneNumber: String): String =
        logAppender.list
            .last { it.formattedMessage.contains(phoneNumber) }
            .formattedMessage
            .let { Regex("""code is (\d{4,8})""").find(it)!!.groupValues[1] }

    /** Same pattern AppReleaseApiIntegrationTest already uses — no HTTP path to self-create a PLATFORM_ADMIN, by design. */
    private fun seedPlatformAdmin(): String {
        val phone = randomPhone()
        userRepository.save(User.registerWithPhone(PhoneNumber(phone), "Reviewer-Access Admin", UserRole.PLATFORM_ADMIN))
        restTemplate.postForEntity(url("/api/v1/auth/otp/request"), OtpRequestRequest(phoneNumber = phone), String::class.java)
        val verify = restTemplate.postForEntity(
            url("/api/v1/auth/otp/verify"),
            OtpVerifyRequest(phoneNumber = phone, code = lastCodeSentTo(phone), fullName = null),
            AuthResponse::class.java,
        )
        return requireNotNull(verify.body).accessToken
    }

    private fun seedReviewerAccount(phone: String): User =
        User.registerWithPhone(PhoneNumber(phone), "Bazaar Reviewer", UserRole.CUSTOMER).also { userRepository.save(it) }

    private fun authedJson(token: String, body: Map<String, Any?>) = HttpEntity(
        body,
        HttpHeaders().apply {
            setBearerAuth(token)
            contentType = MediaType.APPLICATION_JSON
        },
    )

    /** Endpoint/DTOs do not exist yet (RED phase) — raw JSON body, no not-yet-existing request/response classes imported. */
    private fun activateReviewerAccess(adminToken: String, phoneNumber: String, code: String, validityDays: Long? = null) =
        restTemplate.postForEntity(
            url("/api/v1/platform-authority/reviewer-access/activate"),
            authedJson(adminToken, buildMap {
                put("phoneNumber", phoneNumber)
                put("code", code)
                if (validityDays != null) put("validityDays", validityDays)
            }),
            String::class.java,
        )

    private fun deactivateReviewerAccess(adminToken: String, phoneNumber: String) =
        restTemplate.postForEntity(
            url("/api/v1/platform-authority/reviewer-access/deactivate"),
            authedJson(adminToken, mapOf("phoneNumber" to phoneNumber)),
            String::class.java,
        )

    private fun verify(phoneNumber: String, code: String) =
        restTemplate.postForEntity(url("/api/v1/auth/otp/verify"), OtpVerifyRequest(phoneNumber = phoneNumber, code = code), String::class.java)

    @Test
    fun `before activation, the fixed reviewer code does not authenticate`() {
        val phone = randomPhone()
        seedReviewerAccount(phone)

        val response = verify(phone, "1324")

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `after activation, the reviewer phone with the fixed code authenticates with real tokens`() {
        val phone = randomPhone()
        val reviewer = seedReviewerAccount(phone)
        val admin = seedPlatformAdmin()
        activateReviewerAccess(admin, phone, "1324")

        val response = restTemplate.postForEntity(url("/api/v1/auth/otp/verify"), OtpVerifyRequest(phoneNumber = phone, code = "1324"), AuthResponse::class.java)

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = requireNotNull(response.body)
        assertEquals(reviewer.id.value, body.user.id)
        assertTrue(body.accessToken.isNotBlank())
        assertTrue(body.refreshToken.isNotBlank())
    }

    @Test
    fun `a wrong code against the active reviewer phone fails`() {
        val phone = randomPhone()
        seedReviewerAccount(phone)
        val admin = seedPlatformAdmin()
        activateReviewerAccess(admin, phone, "1324")

        val response = verify(phone, "0000")

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `an expired grant does not authenticate`() {
        val phone = randomPhone()
        seedReviewerAccount(phone)
        val admin = seedPlatformAdmin()
        activateReviewerAccess(admin, phone, "1324", validityDays = -1) // already expired the moment it's created

        val response = verify(phone, "1324")

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `a deactivated grant does not authenticate`() {
        val phone = randomPhone()
        seedReviewerAccount(phone)
        val admin = seedPlatformAdmin()
        activateReviewerAccess(admin, phone, "1324")
        deactivateReviewerAccess(admin, phone)

        val response = verify(phone, "1324")

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `the fixed code never authenticates a different phone number, even while another phone's grant is active`() {
        val reviewerPhone = randomPhone()
        val otherPhone = randomPhone()
        seedReviewerAccount(reviewerPhone)
        seedReviewerAccount(otherPhone)
        val admin = seedPlatformAdmin()
        activateReviewerAccess(admin, reviewerPhone, "1324")

        val response = verify(otherPhone, "1324")

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `normal SMS OTP verification remains fully unchanged for an unrelated phone while a reviewer grant exists`() {
        val admin = seedPlatformAdmin()
        activateReviewerAccess(admin, randomPhone(), "1324")

        val normalPhone = randomPhone()
        restTemplate.postForEntity(url("/api/v1/auth/otp/request"), OtpRequestRequest(phoneNumber = normalPhone), String::class.java)
        val code = lastCodeSentTo(normalPhone)

        val response = restTemplate.postForEntity(
            url("/api/v1/auth/otp/verify"),
            OtpVerifyRequest(phoneNumber = normalPhone, code = code, fullName = "Normal User"),
            AuthResponse::class.java,
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(normalPhone, response.body?.user?.phoneNumber)
    }

    /**
     * Deterministic, based on the real configured limit (see [configuredVerifyLimit]
     * and the class-level [TestPropertySource]) — and on the approved design's own
     * disclosed consumption semantics, not a naive "1 call = 1 token" assumption.
     *
     * For a grant-bearing phone, a WRONG-code attempt consumes the shared bucket
     * TWICE per HTTP call (once in [ai.rojan.backend.application.auth.TryReviewerAccessLoginUseCase]'s
     * own check, then again when it falls through to the real, unmodified
     * `VerifyOtpUseCase.enforceVerifyRateLimit`), while a CORRECT-code match
     * consumes it only ONCE (the reviewer path returns directly and never reaches
     * `VerifyOtpUseCase` at all). This is the explicitly approved "stricter, not
     * weaker" trade-off — this test proves it lands exactly on the real
     * configured limit, not a guess: with [configuredVerifyLimit] = 3, one wrong
     * (-2) then one correct (-1) exactly fills the bucket and both succeed; a
     * third call of any kind, correct code included, must then be rejected.
     */
    @Test
    fun `the shared verify rate-limit enforces its real configured limit for the reviewer phone, and the reviewer path never bypasses it`() {
        val phone = randomPhone()
        seedReviewerAccount(phone)
        val admin = seedPlatformAdmin()
        activateReviewerAccess(admin, phone, "1324")

        // 1st call: wrong code -> consumes 2 (reviewer-check + fallback to the real OTP path). Bucket: 0 -> 2, still <= 3.
        val wrongCode = verify(phone, "0000")
        assertEquals(HttpStatus.UNAUTHORIZED, wrongCode.statusCode, "a wrong code, still within the limit, should fail only because the code is wrong, not because of rate limiting")

        // 2nd call: correct code -> the reviewer path matches and returns directly, consuming only 1. Bucket: 2 -> 3, exactly at the limit, still allowed.
        val correctCode = restTemplate.postForEntity(url("/api/v1/auth/otp/verify"), OtpVerifyRequest(phoneNumber = phone, code = "1324"), AuthResponse::class.java)
        assertEquals(HttpStatus.OK, correctCode.statusCode, "the correct code, landing exactly on the configured limit, should still succeed")

        // 3rd call: bucket is now exactly at configuredVerifyLimit (3) — ANY further call, even with the correct code, must be rejected by the shared rate limiter before the code is ever compared.
        val overLimit = verify(phone, "1324")

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, overLimit.statusCode, "the attempt that would push the shared bucket past $configuredVerifyLimit, even with the correct code, must be rejected")
    }
}
