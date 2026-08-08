package ai.rojan.backend.bootstrap

import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles

/**
 * Regression guard: outside the "prod" profile (local/dev/CI), the API docs
 * stay publicly browsable exactly as before Phase 1 - the new profile-aware
 * gate in SecurityConfig.kt must not affect the default developer workflow.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class SwaggerDefaultProfileExposureIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private val restTemplate = TestRestTemplate()

    private fun url(path: String) = "http://localhost:$port$path"

    @Test
    fun `swagger-ui stays publicly reachable outside the prod profile`() {
        val response = restTemplate.getForEntity(url("/swagger-ui/index.html"), String::class.java)
        assertEquals(HttpStatus.OK, response.statusCode)
    }

    @Test
    fun `v3 api-docs stays publicly reachable outside the prod profile`() {
        val response = restTemplate.getForEntity(url("/v3/api-docs"), String::class.java)
        assertEquals(HttpStatus.OK, response.statusCode)
    }
}
