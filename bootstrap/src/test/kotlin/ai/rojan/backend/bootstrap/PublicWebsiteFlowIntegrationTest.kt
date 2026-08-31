package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.common.ApiError
import ai.rojan.backend.api.salon.CreateSalonRequest
import ai.rojan.backend.api.salon.CreateServiceCategoryRequest
import ai.rojan.backend.api.salon.CreateServiceRequest
import ai.rojan.backend.api.salon.CreateSpecialistRequest
import ai.rojan.backend.api.salon.SalonResponse
import ai.rojan.backend.api.salon.ServiceCategoryResponse
import ai.rojan.backend.api.salon.ServiceResponse
import ai.rojan.backend.api.salon.SpecialistResponse
import ai.rojan.backend.api.schedule.SetWorkingHoursRequest
import ai.rojan.backend.api.schedule.TimeIntervalDto
import ai.rojan.backend.api.schedule.WorkingHoursResponse
import ai.rojan.backend.api.website.PublicWebsiteResponse
import ai.rojan.backend.domain.user.UserRole
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import java.math.BigDecimal
import java.time.LocalTime

/**
 * ROJAN AI Website Builder foundation: end-to-end verification of the real
 * `GET /api/v1/public/{tenantSlug}/website` implementation against a real (embedded, no-Docker)
 * PostgreSQL and the actual HTTP layer - proves the real root-cause fix for every tenant subdomain
 * 404ing (the endpoint used to return the same hardcoded `{"name":"ROJAN AI",...}` for every slug,
 * carrying neither `theme` nor `seo`, which ROJAN_Web's own malformed-response guard correctly
 * rejected as not-found). Reuses `PublicSalonDirectoryFlowIntegrationTest`'s own
 * `createAndActivateSalon` pattern - the real minimum `ActivateSalonUseCase` requires - so this
 * proves the real, activated-salon path, not a shortcut.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class PublicWebsiteFlowIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private val restTemplate = TestRestTemplate()

    private fun url(path: String) = "http://localhost:$port$path"

    private fun bearer(token: String) = HttpHeaders().apply { setBearerAuth(token) }

    private fun registerAndLogin(): String {
        val email = "website.${System.nanoTime()}@example.com"
        restTemplate.postForEntity(
            url("/api/v1/auth/register"),
            RegisterRequest(email = email, password = "supersecret123", fullName = "Owner", role = UserRole.MANAGER),
            UserResponse::class.java,
        )
        val login = restTemplate.postForEntity(
            url("/api/v1/auth/login"),
            LoginRequest(email = email, password = "supersecret123"),
            AuthResponse::class.java,
        )
        return requireNotNull(login.body).accessToken
    }

    /** Creates a salon with one active service, one active specialist, and one working-hours day - the real minimum `ActivateSalonUseCase` requires - then activates it. Returns the real, saved, activated `SalonResponse` together with its real owner's token, so a caller that also needs to act as this same owner (e.g. deactivating it) never has to guess/reuse an unrelated token. */
    private fun createAndActivateSalon(name: String): Pair<SalonResponse, String> {
        val ownerToken = registerAndLogin()

        val salon = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons"),
                HttpMethod.POST,
                HttpEntity(CreateSalonRequest(name, "A real salon description", "+1 555 0100", "hello@example.com", "1 Main St"), bearer(ownerToken)),
                SalonResponse::class.java,
            ).body,
        )
        val category = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salon.id}/categories"),
                HttpMethod.POST,
                HttpEntity(CreateServiceCategoryRequest("Hair", null), bearer(ownerToken)),
                ServiceCategoryResponse::class.java,
            ).body,
        )
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/categories/${category.id}/services"),
            HttpMethod.POST,
            HttpEntity(CreateServiceRequest("Haircut", null, 30, BigDecimal("25.00")), bearer(ownerToken)),
            ServiceResponse::class.java,
        )
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists"),
            HttpMethod.POST,
            HttpEntity(CreateSpecialistRequest(null, "Jamie Stylist", null, null, "+989120000006", "Stylist"), bearer(ownerToken)),
            SpecialistResponse::class.java,
        )
        restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/working-hours/MONDAY"),
            HttpMethod.PUT,
            HttpEntity(SetWorkingHoursRequest(listOf(TimeIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)))), bearer(ownerToken)),
            WorkingHoursResponse::class.java,
        )
        restTemplate.exchange(url("/api/v1/salons/${salon.id}/activate"), HttpMethod.POST, HttpEntity<Void>(bearer(ownerToken)), SalonResponse::class.java)
        return salon to ownerToken
    }

    @Test
    fun `returns real, non-placeholder website data for an active salon's real slug - the actual production bug`() {
        val (salon, _) = createAndActivateSalon("Real Website Salon ${System.nanoTime()}")

        val response = restTemplate.getForEntity(url("/api/v1/public/${salon.slug}/website"), PublicWebsiteResponse::class.java)

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = requireNotNull(response.body)
        assertEquals(salon.slug, body.subdomain)
        assertEquals(salon.name, body.name)
        assertFalse(body.name == "ROJAN AI", "must never be the old hardcoded placeholder name")
        assertFalse(body.description == "AI Beauty Platform", "must never be the old hardcoded placeholder description")
        // The exact contract shape ROJAN_Web's PublicWebsite type requires - real, non-null theme/seo,
        // which is the actual reason every tenant subdomain 404'd against the old stub.
        assertEquals(salon.name, body.seo.metaTitle)
        assertTrue(body.seo.metaDescription.isNotBlank())
        assertTrue(body.theme.primaryColor.isNotBlank())
        assertTrue(body.theme.fontFamily.isNotBlank())
    }

    @Test
    fun `includes real services and specialists summaries from the same activated salon`() {
        val (salon, _) = createAndActivateSalon("Summary Salon ${System.nanoTime()}")

        val body = requireNotNull(
            restTemplate.getForEntity(url("/api/v1/public/${salon.slug}/website"), PublicWebsiteResponse::class.java).body,
        )

        assertTrue(body.servicesSummary.any { it.name == "Haircut" })
        assertTrue(body.specialistsSummary.any { it.name == "Jamie Stylist" })
    }

    @Test
    fun `returns 404 SALON_NOT_FOUND for an unknown slug`() {
        val response = restTemplate.getForEntity(url("/api/v1/public/no-such-tenant-slug/website"), ApiError::class.java)

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("SALON_NOT_FOUND", requireNotNull(response.body).errorCode)
    }

    @Test
    fun `returns 404 for a still-DRAFT (never activated) tenant - never distinguishable from unknown`() {
        val ownerToken = registerAndLogin()
        val draft = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons"),
                HttpMethod.POST,
                HttpEntity(CreateSalonRequest("Draft Tenant ${System.nanoTime()}", null, "+1 555 0100", null, "1 Main St"), bearer(ownerToken)),
                SalonResponse::class.java,
            ).body,
        )

        val response = restTemplate.getForEntity(url("/api/v1/public/${draft.slug}/website"), ApiError::class.java)

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `returns 404 for a deactivated tenant even though it was previously active`() {
        val (salon, ownerToken) = createAndActivateSalon("To Be Deactivated ${System.nanoTime()}")
        restTemplate.exchange(url("/api/v1/salons/${salon.id}"), HttpMethod.DELETE, HttpEntity<Void>(bearer(ownerToken)), Void::class.java)

        val response = restTemplate.getForEntity(url("/api/v1/public/${salon.slug}/website"), ApiError::class.java)

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `is reachable with no Authorization header at all - genuinely public`() {
        val (salon, _) = createAndActivateSalon("Anon Access Salon ${System.nanoTime()}")

        val response = restTemplate.exchange(
            url("/api/v1/public/${salon.slug}/website"),
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders()),
            PublicWebsiteResponse::class.java,
        )

        assertEquals(HttpStatus.OK, response.statusCode)
    }
}
