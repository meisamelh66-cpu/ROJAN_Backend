package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.OtpRequestRequest
import ai.rojan.backend.api.auth.OtpResendRequest
import ai.rojan.backend.api.auth.OtpVerifyRequest
import ai.rojan.backend.api.auth.UserResponse
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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

/**
 * End-to-end verification of the OTP vertical slice against a real
 * (embedded, no-Docker) PostgreSQL and the actual HTTP layer — the
 * counterpart to [AuthenticationFlowIntegrationTest] for phone + OTP
 * login, which had no bootstrap-level HTTP coverage before this.
 *
 * The `test` profile's [ai.rojan.backend.infrastructure.sms.LoggingSmsProvider]
 * never sends a real SMS — it logs the message containing the code instead.
 * [logAppender] captures that log line so the test can recover the code the
 * same way a real phone's SMS inbox would deliver it, without adding any
 * test-only hook to production code.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class OtpAuthenticationFlowIntegrationTest {

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

    private fun bearer(token: String): HttpEntity<Void> =
        HttpEntity(HttpHeaders().apply { setBearerAuth(token) })

    private fun testPhoneNumber() = "+9891${(1_000_000..9_999_999).random()}"

    /**
     * Pulls the 6-digit code back out of the last SMS logged for [phoneNumber] —
     * mirrors `RecordingSmsProvider.lastCodeSentTo` in the application-layer unit
     * tests. Anchored to "code is " rather than a bare `\d{6}` scan: the log line
     * also contains the phone number itself, which is E.164 and so contains its
     * own runs of 6+ digits that a bare digit-count match would grab instead.
     */
    private fun lastCodeSentTo(phoneNumber: String): String =
        logAppender.list
            .last { it.formattedMessage.contains(phoneNumber) }
            .formattedMessage
            .let { CODE_REGEX.find(it)!!.groupValues[1] }

    @Test
    fun `request, verify (new account auto-registered), and authenticated access all work end-to-end`() {
        val phone = testPhoneNumber()

        val requestResponse = restTemplate.postForEntity(
            url("/api/v1/auth/otp/request"),
            OtpRequestRequest(phoneNumber = phone),
            String::class.java,
        )
        assertEquals(HttpStatus.OK, requestResponse.statusCode)

        val code = lastCodeSentTo(phone)

        val verifyResponse = restTemplate.postForEntity(
            url("/api/v1/auth/otp/verify"),
            OtpVerifyRequest(phoneNumber = phone, code = code, fullName = "OTP Integration Test"),
            AuthResponse::class.java,
        )
        assertEquals(HttpStatus.OK, verifyResponse.statusCode)
        val tokens = requireNotNull(verifyResponse.body)
        assertEquals(phone, tokens.user.phoneNumber)
        assertEquals(null, tokens.user.email)
        assertTrue(tokens.accessToken.isNotBlank())
        assertTrue(tokens.refreshToken.isNotBlank())

        val meResponse = restTemplate.exchange(
            url("/api/v1/users/me"),
            HttpMethod.GET,
            bearer(tokens.accessToken),
            UserResponse::class.java,
        )
        assertEquals(HttpStatus.OK, meResponse.statusCode)
        assertEquals(phone, meResponse.body?.phoneNumber)
    }

    @Test
    fun `verifying the same code twice fails the second time`() {
        val phone = testPhoneNumber()
        restTemplate.postForEntity(url("/api/v1/auth/otp/request"), OtpRequestRequest(phoneNumber = phone), String::class.java)
        val code = lastCodeSentTo(phone)

        val first = restTemplate.postForEntity(url("/api/v1/auth/otp/verify"), OtpVerifyRequest(phoneNumber = phone, code = code), AuthResponse::class.java)
        assertEquals(HttpStatus.OK, first.statusCode)

        val second = restTemplate.postForEntity(url("/api/v1/auth/otp/verify"), OtpVerifyRequest(phoneNumber = phone, code = code), String::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, second.statusCode)
    }

    @Test
    fun `wrong code is rejected`() {
        val phone = testPhoneNumber()
        restTemplate.postForEntity(url("/api/v1/auth/otp/request"), OtpRequestRequest(phoneNumber = phone), String::class.java)
        val realCode = lastCodeSentTo(phone)
        val wrongCode = if (realCode == "000000") "111111" else "000000"

        val response = restTemplate.postForEntity(url("/api/v1/auth/otp/verify"), OtpVerifyRequest(phoneNumber = phone, code = wrongCode), String::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `verifying without a prior request is rejected`() {
        val phone = testPhoneNumber()
        val response = restTemplate.postForEntity(url("/api/v1/auth/otp/verify"), OtpVerifyRequest(phoneNumber = phone, code = "123456"), String::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `an invalid phone number is rejected with 400`() {
        val response = restTemplate.postForEntity(url("/api/v1/auth/otp/request"), OtpRequestRequest(phoneNumber = "not-a-phone-number"), String::class.java)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `resend issues a fresh code that supersedes the original`() {
        val phone = testPhoneNumber()
        restTemplate.postForEntity(url("/api/v1/auth/otp/request"), OtpRequestRequest(phoneNumber = phone), String::class.java)
        val originalCode = lastCodeSentTo(phone)

        val resendResponse = restTemplate.postForEntity(url("/api/v1/auth/otp/resend"), OtpResendRequest(phoneNumber = phone), String::class.java)
        assertEquals(HttpStatus.OK, resendResponse.statusCode)
        val resentCode = lastCodeSentTo(phone)

        val verifyWithOriginal = restTemplate.postForEntity(url("/api/v1/auth/otp/verify"), OtpVerifyRequest(phoneNumber = phone, code = originalCode), String::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, verifyWithOriginal.statusCode)

        val verifyWithResent = restTemplate.postForEntity(url("/api/v1/auth/otp/verify"), OtpVerifyRequest(phoneNumber = phone, code = resentCode), AuthResponse::class.java)
        assertEquals(HttpStatus.OK, verifyWithResent.statusCode)
    }

    @Test
    fun `verifying the same phone number twice on separate occasions returns the same account`() {
        val phone = testPhoneNumber()

        restTemplate.postForEntity(url("/api/v1/auth/otp/request"), OtpRequestRequest(phoneNumber = phone), String::class.java)
        val firstAuth = restTemplate.postForEntity(url("/api/v1/auth/otp/verify"), OtpVerifyRequest(phoneNumber = phone, code = lastCodeSentTo(phone)), AuthResponse::class.java)
        val firstUserId = requireNotNull(firstAuth.body).user.id

        restTemplate.postForEntity(url("/api/v1/auth/otp/request"), OtpRequestRequest(phoneNumber = phone), String::class.java)
        val secondAuth = restTemplate.postForEntity(url("/api/v1/auth/otp/verify"), OtpVerifyRequest(phoneNumber = phone, code = lastCodeSentTo(phone)), AuthResponse::class.java)
        val secondUserId = requireNotNull(secondAuth.body).user.id

        assertEquals(firstUserId, secondUserId)
        assertNotEquals(requireNotNull(firstAuth.body).accessToken, requireNotNull(secondAuth.body).accessToken)
    }

    @Test
    fun `OpenAPI docs describe the otp endpoints`() {
        val response = restTemplate.getForEntity(url("/v3/api-docs"), String::class.java)

        assertEquals(HttpStatus.OK, response.statusCode)
        val docs = requireNotNull(response.body)
        assertTrue(docs.contains("/api/v1/auth/otp/request"))
        assertTrue(docs.contains("/api/v1/auth/otp/resend"))
        assertTrue(docs.contains("/api/v1/auth/otp/verify"))
    }

    private companion object {
        val CODE_REGEX = Regex("code is (\\d{6})")
    }
}
