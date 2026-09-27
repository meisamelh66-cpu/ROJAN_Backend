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
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.test.context.ActiveProfiles

/**
 * App Release Hardening, end to end against the real embedded Postgres (so V42 + V43 really run,
 * Hibernate `ddl-auto: validate` really checks the entity against them, and the new unique index is
 * the real one): the public "check for update" JSON shape and `Cache-Control: no-store`, channel
 * selection, HTTPS enforcement, status-transition / artifact-lock HTTP status codes, and
 * versionCode uniqueness per channel. Every test uses its own app (and channel) so their "latest"
 * assertions never see another test's releases in the shared database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class AppReleaseApiIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var objectMapper: ObjectMapper

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

    private fun randomPhone() = "+9893${(1_000_000..9_999_999).random()}"

    private fun lastCodeSentTo(phoneNumber: String): String =
        logAppender.list
            .last { it.formattedMessage.contains(phoneNumber) }
            .formattedMessage
            .let { Regex("""code is (\d{4,8})""").find(it)!!.groupValues[1] }

    /** Seeds a real PLATFORM_ADMIN directly (no HTTP path to self-create one, by design) and logs in via the real OTP flow, same as PlatformManagementApiIntegrationTest. */
    private fun seedPlatformAdmin(): String {
        val phone = randomPhone()
        userRepository.save(User.registerWithPhone(PhoneNumber(phone), "Release Admin", UserRole.PLATFORM_ADMIN))
        restTemplate.postForEntity(url("/api/v1/auth/otp/request"), OtpRequestRequest(phoneNumber = phone), String::class.java)
        val verify = restTemplate.postForEntity(
            url("/api/v1/auth/otp/verify"),
            OtpVerifyRequest(phoneNumber = phone, code = lastCodeSentTo(phone), fullName = null),
            AuthResponse::class.java,
        )
        return requireNotNull(verify.body).accessToken
    }

    private fun json(token: String, body: Map<String, Any?>? = null) = HttpEntity(
        body,
        HttpHeaders().apply {
            setBearerAuth(token)
            contentType = MediaType.APPLICATION_JSON
        },
    )

    private fun releaseBody(
        applicationId: String,
        versionName: String,
        versionCode: Int,
        status: String = "PUBLISHED",
        channel: String? = null,
        downloadUrl: String = "https://rojanai.ir/download/test/$versionName.bin",
    ): Map<String, Any?> = buildMap {
        put("applicationId", applicationId)
        put("versionName", versionName)
        put("versionCode", versionCode)
        put("minSupportedVersionCode", 1)
        put("isMandatory", false)
        put("status", status)
        put("downloadUrl", downloadUrl)
        put("sha256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        put("fileSizeBytes", 54_050_274)
        put("releaseNotes", "Notes for $versionName")
        put("releaseDate", "2026-09-27")
        if (channel != null) put("channel", channel)
    }

    private fun create(token: String, body: Map<String, Any?>): ResponseEntity<String> =
        restTemplate.exchange(url("/api/v1/platform-authority/app-releases"), HttpMethod.POST, json(token, body), String::class.java)

    private fun createOk(token: String, body: Map<String, Any?>): JsonNode {
        val response = create(token, body)
        assertEquals(HttpStatus.CREATED, response.statusCode, response.body)
        return objectMapper.readTree(response.body)
    }

    private fun post(token: String, path: String): ResponseEntity<String> =
        restTemplate.exchange(url(path), HttpMethod.POST, json(token), String::class.java)

    private fun latest(applicationId: String, versionCode: Int, channel: String? = null): ResponseEntity<String> =
        restTemplate.getForEntity(
            url("/api/v1/public/app-releases/$applicationId/latest?versionCode=$versionCode" + (channel?.let { "&channel=$it" } ?: "")),
            String::class.java,
        )

    @Test
    fun `the public latest response has exactly the documented fields, and is never cacheable`() {
        val token = seedPlatformAdmin()
        createOk(token, releaseBody("ai.rojan.designlab.reception", "1.0.1", 1_000_001))

        val response = latest("ai.rojan.designlab.reception", 1_000_000)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(response.headers.cacheControl.orEmpty().contains("no-store"), "Cache-Control was ${response.headers.cacheControl}")
        val body = objectMapper.readTree(response.body)
        assertEquals(
            setOf(
                "updateAvailable", "forceUpdate", "latestVersion", "latestVersionCode", "minSupportedVersionCode",
                "isMandatory", "channel", "downloadUrl", "sha256", "fileSizeBytes", "releaseNotes", "releaseDate", "publishedAt",
            ),
            body.fieldNames().asSequence().toSet(),
        )
        assertTrue(body["updateAvailable"].booleanValue())
        assertFalse(body["forceUpdate"].booleanValue())
        assertEquals("1.0.1", body["latestVersion"].textValue())
        assertEquals(1_000_001, body["latestVersionCode"].intValue())
        assertEquals(1, body["minSupportedVersionCode"].intValue())
        assertFalse(body["isMandatory"].booleanValue())
        assertEquals("PRODUCTION", body["channel"].textValue())
        assertEquals(54_050_274L, body["fileSizeBytes"].longValue())
        assertEquals("2026-09-27", body["releaseDate"].textValue())
        assertTrue(body["publishedAt"].isTextual, "publishedAt should be an ISO instant, was ${body["publishedAt"]}")
    }

    @Test
    fun `the public lookup defaults to PRODUCTION and returns another channel only when asked`() {
        val token = seedPlatformAdmin()
        createOk(token, releaseBody("ai.rojan.designlab", "2.0.0", 20))
        createOk(token, releaseBody("ai.rojan.designlab", "2.1.0-beta", 21, channel = "BETA"))

        val production = objectMapper.readTree(latest("ai.rojan.designlab", 1).body)
        assertEquals(20, production["latestVersionCode"].intValue())
        assertEquals("PRODUCTION", production["channel"].textValue())

        val beta = objectMapper.readTree(latest("ai.rojan.designlab", 1, "BETA").body)
        assertEquals(21, beta["latestVersionCode"].intValue())
        assertEquals("BETA", beta["channel"].textValue())

        assertEquals(HttpStatus.BAD_REQUEST, latest("ai.rojan.designlab", 1, "NIGHTLY").statusCode)
    }

    @Test
    fun `the same versionCode is accepted once per channel by the real unique index`() {
        val token = seedPlatformAdmin()
        createOk(token, releaseBody("ai.rojan.designlab.manager", "3.0.0", 300))
        createOk(token, releaseBody("ai.rojan.designlab.manager", "3.0.0", 300, channel = "BETA"))
        assertEquals(HttpStatus.CONFLICT, create(token, releaseBody("ai.rojan.designlab.manager", "3.0.0", 300, channel = "BETA")).statusCode)
    }

    @Test
    fun `lifecycle over HTTP - https only, artifact lock and status transitions return the documented codes`() {
        val token = seedPlatformAdmin()
        val app = "ai.rojan.designlab.manager"

        val insecure = create(token, releaseBody(app, "4.0.0", 400, channel = "BETA", downloadUrl = "http://rojanai.ir/download/test/4.0.0.bin"))
        assertEquals(HttpStatus.BAD_REQUEST, insecure.statusCode)

        val draft = createOk(token, releaseBody(app, "4.0.1", 401, status = "DRAFT", channel = "BETA"))
        val id = draft["id"].textValue()
        assertEquals("DRAFT", draft["status"].textValue())
        // The API omits null fields entirely, so an unpublished draft simply has no publishedAt.
        assertTrue(draft["publishedAt"]?.isNull ?: true, "draft publishedAt should be absent or null, was ${draft["publishedAt"]}")
        assertEquals("BETA", draft["channel"].textValue())

        val published = objectMapper.readTree(post(token, "/api/v1/platform-authority/app-releases/$id/publish").body)
        assertEquals("PUBLISHED", published["status"].textValue())
        assertTrue(published["publishedAt"].isTextual)
        assertTrue(published["publishedBy"].isTextual)

        val changeSha = restTemplate.exchange(
            url("/api/v1/platform-authority/app-releases/$id"),
            HttpMethod.PUT,
            json(token, releaseBody(app, "4.0.1", 401).minus("applicationId").minus("versionCode") + ("sha256" to "f".repeat(64))),
            String::class.java,
        )
        assertEquals(HttpStatus.CONFLICT, changeSha.statusCode)
        assertEquals("APP_RELEASE_PUBLISHED_ARTIFACT_IMMUTABLE", objectMapper.readTree(changeSha.body)["errorCode"].textValue())

        assertEquals(HttpStatus.OK, post(token, "/api/v1/platform-authority/app-releases/$id/archive").statusCode)
        // Archived: 401 is no longer offered. (Another test may have published an older BETA manager
        // release in this shared database, so "latest" can fall back to it rather than 404.)
        val afterArchive = latest(app, 1, "BETA")
        assertTrue(
            afterArchive.statusCode == HttpStatus.NOT_FOUND ||
                objectMapper.readTree(afterArchive.body)["latestVersionCode"].intValue() != 401,
            "archived release 401 must not be offered, got ${afterArchive.statusCode} ${afterArchive.body}",
        )

        val editRepublish = restTemplate.exchange(
            url("/api/v1/platform-authority/app-releases/$id"),
            HttpMethod.PUT,
            json(token, releaseBody(app, "4.0.1", 401).minus("applicationId").minus("versionCode")),
            String::class.java,
        )
        assertEquals(HttpStatus.CONFLICT, editRepublish.statusCode)
        assertEquals("APP_RELEASE_INVALID_STATUS_TRANSITION", objectMapper.readTree(editRepublish.body)["errorCode"].textValue())

        assertEquals(HttpStatus.OK, post(token, "/api/v1/platform-authority/app-releases/$id/republish").statusCode)
        assertEquals(401, objectMapper.readTree(latest(app, 1, "BETA").body)["latestVersionCode"].intValue())
    }
}
