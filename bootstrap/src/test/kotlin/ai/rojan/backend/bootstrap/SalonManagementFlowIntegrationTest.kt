package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.salon.BranchResponse
import ai.rojan.backend.api.salon.CreateBranchRequest
import ai.rojan.backend.api.salon.CreateSalonRequest
import ai.rojan.backend.api.salon.CreateServiceCategoryRequest
import ai.rojan.backend.api.salon.CreateServiceRequest
import ai.rojan.backend.api.salon.CreateSpecialistRequest
import ai.rojan.backend.api.salon.SalonResponse
import ai.rojan.backend.api.salon.ServiceCategoryResponse
import ai.rojan.backend.api.salon.ServiceResponse
import ai.rojan.backend.api.salon.SpecialistResponse
import ai.rojan.backend.api.salon.UpdateSalonRequest
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

/**
 * End-to-end verification of the salon-management vertical against a real
 * (embedded, no-Docker) PostgreSQL and the actual HTTP layer, exercising the
 * full Salon -> Branch / ServiceCategory -> Service / Specialist hierarchy
 * plus ownership authorization and cross-tenant isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class SalonManagementFlowIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private val restTemplate = TestRestTemplate()

    private fun url(path: String) = "http://localhost:$port$path"

    private fun bearer(token: String) = HttpHeaders().apply { setBearerAuth(token) }

    private fun registerAndLogin(): String {
        val email = "salon.${System.nanoTime()}@example.com"
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

    @Test
    fun `full salon hierarchy can be created, browsed, updated, and deactivated by its owner`() {
        val ownerToken = registerAndLogin()

        val createSalon = restTemplate.exchange(
            url("/api/v1/salons"),
            HttpMethod.POST,
            HttpEntity(
                CreateSalonRequest("Glow Salon", "Full service", "+1 555 0100", "hello@glow.example", "1 Main St"),
                bearer(ownerToken),
            ),
            SalonResponse::class.java,
        )
        assertEquals(HttpStatus.CREATED, createSalon.statusCode)
        val salon = requireNotNull(createSalon.body)
        assertTrue(salon.active)

        val listSalons = restTemplate.exchange(
            url("/api/v1/salons"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)),
            Array<SalonResponse>::class.java,
        )
        assertEquals(HttpStatus.OK, listSalons.statusCode)
        assertTrue(listSalons.body!!.any { it.id == salon.id })

        val createBranch = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/branches"),
            HttpMethod.POST,
            HttpEntity(CreateBranchRequest("Downtown", "10 Center Ave", "+1 555 0111"), bearer(ownerToken)),
            BranchResponse::class.java,
        )
        assertEquals(HttpStatus.CREATED, createBranch.statusCode)

        val createCategory = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/categories"),
            HttpMethod.POST,
            HttpEntity(CreateServiceCategoryRequest("Hair", "Cuts and styling"), bearer(ownerToken)),
            ServiceCategoryResponse::class.java,
        )
        assertEquals(HttpStatus.CREATED, createCategory.statusCode)
        val category = requireNotNull(createCategory.body)

        val createService = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/categories/${category.id}/services"),
            HttpMethod.POST,
            HttpEntity(CreateServiceRequest("Haircut", "Classic cut", 30, BigDecimal("25.00")), bearer(ownerToken)),
            ServiceResponse::class.java,
        )
        assertEquals(HttpStatus.CREATED, createService.statusCode)
        val service = requireNotNull(createService.body)
        assertEquals(0, BigDecimal("25.00").compareTo(service.price))

        val createSpecialist = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists"),
            HttpMethod.POST,
            HttpEntity(
                CreateSpecialistRequest(null, "Jamie Stylist", "10 years experience", null),
                bearer(ownerToken),
            ),
            SpecialistResponse::class.java,
        )
        assertEquals(HttpStatus.CREATED, createSpecialist.statusCode)

        val updateSalon = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}"),
            HttpMethod.PUT,
            HttpEntity(
                UpdateSalonRequest("Glow Salon & Spa", null, "+1 555 0199", null, "2 Main St"),
                bearer(ownerToken),
            ),
            SalonResponse::class.java,
        )
        assertEquals(HttpStatus.OK, updateSalon.statusCode)
        assertEquals("Glow Salon & Spa", updateSalon.body?.name)

        val deactivateService = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/categories/${category.id}/services/${service.id}"),
            HttpMethod.DELETE,
            HttpEntity<Void>(bearer(ownerToken)),
            Void::class.java,
        )
        assertEquals(HttpStatus.NO_CONTENT, deactivateService.statusCode)

        val deactivateSalon = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}"),
            HttpMethod.DELETE,
            HttpEntity<Void>(bearer(ownerToken)),
            Void::class.java,
        )
        assertEquals(HttpStatus.NO_CONTENT, deactivateSalon.statusCode)

        val getAfterDeactivate = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)),
            SalonResponse::class.java,
        )
        assertEquals(HttpStatus.OK, getAfterDeactivate.statusCode)
        assertFalse(getAfterDeactivate.body!!.active)
    }

    @Test
    fun `rejects mutation from a caller who does not own the salon`() {
        val ownerToken = registerAndLogin()
        val strangerToken = registerAndLogin()

        val salon = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons"),
                HttpMethod.POST,
                HttpEntity(
                    CreateSalonRequest("Private Salon", null, "+1 555 0200", null, "5 Side St"),
                    bearer(ownerToken),
                ),
                SalonResponse::class.java,
            ).body,
        )

        val response = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}"),
            HttpMethod.PUT,
            HttpEntity(
                UpdateSalonRequest("Hijacked", null, "+1 555 0299", null, "Nowhere"),
                bearer(strangerToken),
            ),
            String::class.java,
        )

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `returns 404 for a branch that belongs to a different salon`() {
        val ownerToken = registerAndLogin()

        val salonA = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons"),
                HttpMethod.POST,
                HttpEntity(CreateSalonRequest("Salon A", null, "+1 555 0300", null, "A St"), bearer(ownerToken)),
                SalonResponse::class.java,
            ).body,
        )
        val salonB = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons"),
                HttpMethod.POST,
                HttpEntity(CreateSalonRequest("Salon B", null, "+1 555 0400", null, "B St"), bearer(ownerToken)),
                SalonResponse::class.java,
            ).body,
        )
        val branchOfA = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons/${salonA.id}/branches"),
                HttpMethod.POST,
                HttpEntity(CreateBranchRequest("A Branch", "A Ave", "+1 555 0301"), bearer(ownerToken)),
                BranchResponse::class.java,
            ).body,
        )

        val crossTenantGet = restTemplate.exchange(
            url("/api/v1/salons/${salonB.id}/branches/${branchOfA.id}"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)),
            String::class.java,
        )

        assertEquals(HttpStatus.NOT_FOUND, crossTenantGet.statusCode)
    }

    @Test
    fun `rejects unauthenticated salon creation`() {
        val response = restTemplate.postForEntity(
            url("/api/v1/salons"),
            CreateSalonRequest("No Auth Salon", null, "+1 555 0500", null, "Nowhere"),
            String::class.java,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `OpenAPI docs describe the salon management endpoints`() {
        val response = restTemplate.getForEntity(url("/v3/api-docs"), String::class.java)

        assertEquals(HttpStatus.OK, response.statusCode)
        val docs = requireNotNull(response.body)
        assertTrue(docs.contains("/api/v1/salons"))
        assertTrue(docs.contains("/api/v1/salons/{salonId}/branches"))
        assertTrue(docs.contains("/api/v1/salons/{salonId}/categories"))
        assertTrue(docs.contains("/api/v1/salons/{salonId}/categories/{categoryId}/services"))
        assertTrue(docs.contains("/api/v1/salons/{salonId}/specialists"))
    }
}
