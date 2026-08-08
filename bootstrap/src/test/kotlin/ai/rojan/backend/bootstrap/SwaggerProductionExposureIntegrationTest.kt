package ai.rojan.backend.bootstrap

import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource

/**
 * Proves the Phase 1 defense-in-depth fix in SecurityConfig.kt: under the
 * "prod" profile, the swagger-ui and v3 api-docs paths require
 * authentication even if springdoc is force-enabled - closing the gap
 * where the permitAll list was previously profile-unaware. springdoc is
 * force-enabled here (overriding application-prod.yml's normal
 * default-off) specifically to prove SecurityConfig's own gate is what
 * blocks access, not merely springdoc being absent. logging.file.name is
 * blanked out to avoid application-prod.yml's real log-file path writing
 * outside the test sandbox. See SwaggerDefaultProfileExposureIntegrationTest
 * for proof the non-prod (dev/test) workflow is unchanged.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test", "prod")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
@TestPropertySource(
    properties = [
        "logging.file.name=",
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=true",
    ],
)
class SwaggerProductionExposureIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private val restTemplate = TestRestTemplate()

    private fun url(path: String) = "http://localhost:$port$path"

    @Test
    fun `swagger-ui requires authentication under the prod profile, even with springdoc force-enabled`() {
        val response = restTemplate.getForEntity(url("/swagger-ui/index.html"), String::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `v3 api-docs requires authentication under the prod profile, even with springdoc force-enabled`() {
        val response = restTemplate.getForEntity(url("/v3/api-docs"), String::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `health endpoint remains reachable without authentication under the prod profile`() {
        // See CorsConfigurationIntegrationTest for why this checks "not
        // auth-gated" rather than a specific status - this sandbox has no
        // local Redis/Kafka, so 503 is possible here independent of security.
        val response = restTemplate.getForEntity(url("/actuator/health"), String::class.java)
        assertTrue(response.statusCode != HttpStatus.UNAUTHORIZED && response.statusCode != HttpStatus.FORBIDDEN)
    }
}
