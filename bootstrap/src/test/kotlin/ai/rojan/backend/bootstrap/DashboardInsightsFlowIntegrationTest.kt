package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.dashboard.DashboardInsightsResponse
import ai.rojan.backend.api.invite.SalonInviteAcceptedResponse
import ai.rojan.backend.api.salon.CreateSalonInviteRequest
import ai.rojan.backend.api.salon.CreateSalonRequest
import ai.rojan.backend.api.salon.SalonInviteResponse
import ai.rojan.backend.api.salon.SalonResponse
import ai.rojan.backend.domain.salon.SalonRole
import ai.rojan.backend.domain.user.UserRole
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
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
import java.util.UUID

/**
 * End-to-end verification of the dashboard-insights vertical: auth-token
 * handling, implicit salon-context resolution (no `salonId` param), tenant
 * isolation, and the response shape ROJAN Web's AI Insights Card consumes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class DashboardInsightsFlowIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private val restTemplate = TestRestTemplate()

    private fun url(path: String) = "http://localhost:$port$path"

    private fun bearer(token: String) = HttpHeaders().apply { setBearerAuth(token) }

    private fun registerAndLogin(role: UserRole): String {
        val email = "dashboard.${System.nanoTime()}@example.com"
        restTemplate.postForEntity(
            url("/api/v1/auth/register"),
            RegisterRequest(email = email, password = "supersecret123", fullName = "Test $role", role = role),
            UserResponse::class.java,
        )
        val login = restTemplate.postForEntity(
            url("/api/v1/auth/login"),
            LoginRequest(email = email, password = "supersecret123"),
            AuthResponse::class.java,
        )
        return requireNotNull(login.body).accessToken
    }

    private fun createSalon(token: String, name: String): SalonResponse = requireNotNull(
        restTemplate.exchange(
            url("/api/v1/salons"),
            HttpMethod.POST,
            HttpEntity(CreateSalonRequest(name, null, "+1 555 0100", null, "1 Main St"), bearer(token)),
            SalonResponse::class.java,
        ).body,
    )

    private fun createInvite(ownerToken: String, salonId: UUID, role: SalonRole): SalonInviteResponse = requireNotNull(
        restTemplate.exchange(
            url("/api/v1/salons/$salonId/invites"),
            HttpMethod.POST,
            HttpEntity(CreateSalonInviteRequest(role), bearer(ownerToken)),
            SalonInviteResponse::class.java,
        ).body,
    )

    private fun acceptInvite(token: String, inviteToken: String): SalonInviteAcceptedResponse = requireNotNull(
        restTemplate.exchange(
            url("/api/v1/invites/$inviteToken/accept"),
            HttpMethod.POST,
            HttpEntity<Void>(bearer(token)),
            SalonInviteAcceptedResponse::class.java,
        ).body,
    )

    @Test
    fun `insights requires a bearer token, and returns the standard AUTH_UNAUTHORIZED contract`() {
        val response = restTemplate.getForEntity(url("/api/v1/dashboard/insights"), String::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
        assertEquals("""{"errorCode":"AUTH_UNAUTHORIZED","message":"Authentication required"}""", response.body)
    }

    @Test
    fun `an owner with no salon gets 404, not a validation error about a missing salonId`() {
        val customerToken = registerAndLogin(UserRole.CUSTOMER)

        val response = restTemplate.exchange(
            url("/api/v1/dashboard/insights"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(customerToken)),
            String::class.java,
        )
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `an owner with exactly one salon gets a complete, zeroed empty-state response with no salonId param`() {
        val managerToken = registerAndLogin(UserRole.MANAGER)
        createSalon(managerToken, "Solo Salon")

        val response = restTemplate.exchange(
            url("/api/v1/dashboard/insights"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(managerToken)),
            DashboardInsightsResponse::class.java,
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = requireNotNull(response.body)
        assertEquals(BigDecimal.ZERO.setScale(body.revenue.today.scale()), body.revenue.today)
        assertEquals(0L, body.bookings.total)
        assertEquals(0, body.customers.newCustomers)
        assertTrue(body.services.isEmpty())
        assertTrue(body.recommendations.isEmpty())
    }

    @Test
    fun `an owner with two salons gets 409 since context cannot be resolved implicitly`() {
        val managerToken = registerAndLogin(UserRole.MANAGER)
        createSalon(managerToken, "First Salon")
        createSalon(managerToken, "Second Salon")

        val response = restTemplate.exchange(
            url("/api/v1/dashboard/insights"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(managerToken)),
            String::class.java,
        )
        assertEquals(HttpStatus.CONFLICT, response.statusCode)
    }

    @Test
    fun `a MANAGER member gets 200 via explicit salonId - RBAC, not ownerId equality`() {
        val ownerToken = registerAndLogin(UserRole.CUSTOMER)
        val salon = createSalon(ownerToken, "Owner's Salon")
        val invite = createInvite(ownerToken, salon.id, SalonRole.MANAGER)

        val managerToken = registerAndLogin(UserRole.CUSTOMER)
        acceptInvite(managerToken, invite.token)

        val response = restTemplate.exchange(
            url("/api/v1/dashboard/insights?salonId=${salon.id}"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(managerToken)),
            DashboardInsightsResponse::class.java,
        )

        assertEquals(HttpStatus.OK, response.statusCode)
    }

    @Test
    fun `a RECEPTIONIST member is denied - lacks VIEW_CRM`() {
        val ownerToken = registerAndLogin(UserRole.CUSTOMER)
        val salon = createSalon(ownerToken, "Owner's Salon")
        val invite = createInvite(ownerToken, salon.id, SalonRole.RECEPTIONIST)

        val receptionToken = registerAndLogin(UserRole.CUSTOMER)
        acceptInvite(receptionToken, invite.token)

        val response = restTemplate.exchange(
            url("/api/v1/dashboard/insights?salonId=${salon.id}"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(receptionToken)),
            String::class.java,
        )

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `an unrelated authenticated user is denied - 403, not 404, so salon existence is never leaked`() {
        val ownerToken = registerAndLogin(UserRole.CUSTOMER)
        val salon = createSalon(ownerToken, "Owner's Salon")

        val strangerToken = registerAndLogin(UserRole.CUSTOMER)

        val response = restTemplate.exchange(
            url("/api/v1/dashboard/insights?salonId=${salon.id}"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(strangerToken)),
            String::class.java,
        )

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `OpenAPI docs describe the dashboard insights endpoint`() {
        val response = restTemplate.getForEntity(url("/v3/api-docs"), String::class.java)

        assertEquals(HttpStatus.OK, response.statusCode)
        val docs = requireNotNull(response.body)
        assertTrue(docs.contains("/api/v1/dashboard/insights"))
    }
}
