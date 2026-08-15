package ai.rojan.backend.bootstrap

import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

/**
 * Proves the Phase 1 CORS allow-list (CorsConfig.kt / SecurityConfig.kt):
 * exactly one explicit origin is granted CORS response headers, every other
 * origin is rejected, and this holds for both preflight (OPTIONS) and an
 * actual cross-origin request. The "test" profile's allowed origin comes
 * from the base application.yml default (http://localhost:3000) - no
 * profile-specific override needed for this test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class CorsConfigurationIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private val restTemplate = TestRestTemplate()

    private fun url(path: String) = "http://localhost:$port$path"

    private val allowedOrigin = "http://localhost:3000"
    private val disallowedOrigin = "http://evil-example.com"

    @Test
    fun `preflight from the allowed origin receives an explicit CORS grant`() {
        val headers = HttpHeaders().apply {
            set("Origin", allowedOrigin)
            set("Access-Control-Request-Method", "GET")
            set("Access-Control-Request-Headers", "authorization,content-type")
        }

        val response = restTemplate.exchange(
            url("/api/v1/public/salons/nonexistent-slug"),
            HttpMethod.OPTIONS,
            HttpEntity<Void>(headers),
            String::class.java,
        )

        assertEquals(allowedOrigin, response.headers.getFirst("Access-Control-Allow-Origin"))
        assertNull(response.headers.getFirst("Access-Control-Allow-Credentials"))
    }

    @Test
    fun `preflight from an unauthorized origin receives no CORS grant and is rejected`() {
        val headers = HttpHeaders().apply {
            set("Origin", disallowedOrigin)
            set("Access-Control-Request-Method", "GET")
            set("Access-Control-Request-Headers", "authorization,content-type")
        }

        val response = restTemplate.exchange(
            url("/api/v1/public/salons/nonexistent-slug"),
            HttpMethod.OPTIONS,
            HttpEntity<Void>(headers),
            String::class.java,
        )

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        assertNull(response.headers.getFirst("Access-Control-Allow-Origin"))
    }

    @Test
    fun `an actual cross-origin GET from the allowed origin still carries the CORS grant header`() {
        val headers = HttpHeaders().apply { set("Origin", allowedOrigin) }

        val response = restTemplate.exchange(
            url("/api/v1/public/salons/nonexistent-slug"),
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            String::class.java,
        )

        assertEquals(allowedOrigin, response.headers.getFirst("Access-Control-Allow-Origin"))
    }

    @Test
    fun `an actual cross-origin GET from an unauthorized origin is rejected`() {
        val headers = HttpHeaders().apply { set("Origin", disallowedOrigin) }

        val response = restTemplate.exchange(
            url("/api/v1/public/salons/nonexistent-slug"),
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            String::class.java,
        )

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        assertNull(response.headers.getFirst("Access-Control-Allow-Origin"))
    }

    @Test
    fun `health endpoint remains reachable without authentication, unaffected by the CORS change`() {
        // Asserts "not gated by security" rather than a specific status: this
        // sandbox has no local Redis/Kafka, so the actuator's own downstream
        // dependency check can legitimately report 503 here independent of
        // anything in this phase - that's pre-existing, unrelated to CORS/
        // SecurityConfig. What matters for this test is that PUBLIC_ENDPOINTS
        // still covers this path, i.e. it's never a 401/403.
        val response = restTemplate.getForEntity(url("/actuator/health"), String::class.java)
        assertTrue(response.statusCode != HttpStatus.UNAUTHORIZED && response.statusCode != HttpStatus.FORBIDDEN)
    }
}
