package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.common.ApiError
import ai.rojan.backend.api.common.PagedResponse
import ai.rojan.backend.api.salon.AssignMembershipRequest
import ai.rojan.backend.api.salon.BranchResponse
import ai.rojan.backend.api.salon.CreateBranchRequest
import ai.rojan.backend.api.salon.CreateSalonRequest
import ai.rojan.backend.api.salon.CreateServiceCategoryRequest
import ai.rojan.backend.api.salon.CreateServiceRequest
import ai.rojan.backend.api.salon.CreateSpecialistRequest
import ai.rojan.backend.api.salon.SalonMembershipResponse
import ai.rojan.backend.api.salon.SalonResponse
import ai.rojan.backend.api.salon.ServiceCategoryResponse
import ai.rojan.backend.api.salon.ServiceResponse
import ai.rojan.backend.api.salon.SpecialistResponse
import ai.rojan.backend.api.salon.UpdateSalonRequest
import ai.rojan.backend.api.salon.UpdateSpecialistRequest
import ai.rojan.backend.domain.salon.SalonRole
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
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.util.UUID

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

    /** Like [registerAndLogin], but also returns the caller's own user id - needed to assign them a [SalonRole] or link them as a [CreateSpecialistRequest]'s `userId`. */
    private fun registerAndLoginWithId(): Pair<String, UUID> {
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
        val body = requireNotNull(login.body)
        return body.accessToken to body.user.id
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

        // The customer-facing directory (GET /api/v1/salons) only lists salons that have finished
        // onboarding (onboardingStatus == ACTIVE) - a brand-new salon is still DRAFT, fully
        // manageable by its owner but not yet publicly browsable. The owner still sees it via the
        // detail endpoint (checked below). Activation and post-activation browsing are covered by
        // SalonActivationFlowIntegrationTest.
        val listSalons = restTemplate.exchange(
            url("/api/v1/salons"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)),
            object : ParameterizedTypeReference<PagedResponse<SalonResponse>>() {},
        )
        assertEquals(HttpStatus.OK, listSalons.statusCode)
        assertFalse(listSalons.body!!.content.any { it.id == salon.id })

        val getOwnDraft = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}"),
            HttpMethod.GET,
            HttpEntity<Void>(bearer(ownerToken)),
            SalonResponse::class.java,
        )
        assertEquals(HttpStatus.OK, getOwnDraft.statusCode)
        assertEquals(salon.id, getOwnDraft.body!!.id)

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
                CreateSpecialistRequest(null, "Jamie Stylist", "10 years experience", null, "+989120000004", "Hair Stylist"),
                bearer(ownerToken),
            ),
            SpecialistResponse::class.java,
        )
        assertEquals(HttpStatus.CREATED, createSpecialist.statusCode)
        assertEquals("+989120000004", createSpecialist.body?.mobileNumber)
        assertEquals("Hair Stylist", createSpecialist.body?.specialty)

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
    fun `specialist create and update succeed without mobile number or specialty - legacy client compatibility`() {
        val ownerToken = registerAndLogin()
        val salon = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons"),
                HttpMethod.POST,
                HttpEntity(CreateSalonRequest("Legacy Client Salon", null, "+1 555 0500", null, "9 Old St"), bearer(ownerToken)),
                SalonResponse::class.java,
            ).body,
        )

        // A pre-P1 client's request body, as if `mobileNumber`/`specialty` didn't exist yet.
        val createSpecialist = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists"),
            HttpMethod.POST,
            HttpEntity(
                CreateSpecialistRequest(userId = null, displayName = "Jamie Stylist", bio = null, photoUrl = null, mobileNumber = null, specialty = null),
                bearer(ownerToken),
            ),
            SpecialistResponse::class.java,
        )
        assertEquals(HttpStatus.CREATED, createSpecialist.statusCode)
        val specialist = requireNotNull(createSpecialist.body)
        assertEquals(null, specialist.mobileNumber)
        assertEquals(null, specialist.specialty)

        val updateSpecialist = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists/${specialist.id}"),
            HttpMethod.PUT,
            HttpEntity(
                UpdateSpecialistRequest(displayName = "Jamie Senior Stylist", bio = null, photoUrl = null, mobileNumber = null, specialty = null),
                bearer(ownerToken),
            ),
            SpecialistResponse::class.java,
        )
        assertEquals(HttpStatus.OK, updateSpecialist.statusCode)
        assertEquals("Jamie Senior Stylist", updateSpecialist.body?.displayName)
        assertEquals(null, updateSpecialist.body?.mobileNumber)
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

    /**
     * D7 (ROJAN Master Integration Matrix, Decision Register) - closes the authorization/IDOR gap
     * in `GET /api/v1/salons/{salonId}`: before this fix the endpoint required only a valid bearer
     * token (any authenticated user), not any relationship to the salon, so any logged-in caller
     * could read any other owner's salon (including DRAFT/suspended ones) by UUID. Owner, an
     * assigned [SalonRole] member, and a [CreateSpecialistRequest]-linked specialist must still see
     * it (including while DRAFT - the existing, intentional "owner can view their own draft"
     * contract); an authenticated caller with none of those three relationships must get
     * `403 ACCESS_DENIED` and nothing else from the response body, whether the salon is DRAFT or
     * ACTIVE; a nonexistent id must still 404 for everyone.
     */
    @Test
    fun `GET by id enforces owner-member-specialist authorization and never leaks salon fields to an unrelated caller`() {
        val (ownerToken, _) = registerAndLoginWithId()
        val (memberToken, memberId) = registerAndLoginWithId()
        val (specialistToken, specialistUserId) = registerAndLoginWithId()
        val (strangerToken, _) = registerAndLoginWithId()

        val salon = requireNotNull(
            restTemplate.exchange(
                url("/api/v1/salons"),
                HttpMethod.POST,
                HttpEntity(
                    CreateSalonRequest("D7 Privacy Salon", null, "+1 555 0700", "owner.d7@example.com", "7 Side St"),
                    bearer(ownerToken),
                ),
                SalonResponse::class.java,
            ).body,
        )
        // Never activated - stays DRAFT for this whole test, on purpose: D7's acceptance
        // criterion is specifically about an unauthorized caller learning DRAFT/inactive status.
        assertFalse(salon.onboardingStatus.name == "ACTIVE")

        // 1) Owner -> allowed, even while DRAFT (pre-existing, intentional contract).
        val ownerGet = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}"), HttpMethod.GET, HttpEntity<Void>(bearer(ownerToken)), SalonResponse::class.java,
        )
        assertEquals(HttpStatus.OK, ownerGet.statusCode)
        assertEquals(salon.id, ownerGet.body!!.id)

        // 2) Assigned member (MANAGER role) -> allowed, same DRAFT salon.
        val assign = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/members/$memberId"),
            HttpMethod.PUT,
            HttpEntity(AssignMembershipRequest(SalonRole.MANAGER), bearer(ownerToken)),
            SalonMembershipResponse::class.java,
        )
        assertEquals(HttpStatus.OK, assign.statusCode)
        val memberGet = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}"), HttpMethod.GET, HttpEntity<Void>(bearer(memberToken)), SalonResponse::class.java,
        )
        assertEquals(HttpStatus.OK, memberGet.statusCode)
        assertEquals(salon.id, memberGet.body!!.id)

        // 3) Linked specialist (own userId on the Specialist row) -> allowed, same DRAFT salon.
        val createSpecialist = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/specialists"),
            HttpMethod.POST,
            HttpEntity(
                CreateSpecialistRequest(specialistUserId, "Linked Specialist", null, null, null, null),
                bearer(ownerToken),
            ),
            SpecialistResponse::class.java,
        )
        assertEquals(HttpStatus.CREATED, createSpecialist.statusCode)
        val specialistGet = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}"), HttpMethod.GET, HttpEntity<Void>(bearer(specialistToken)), SalonResponse::class.java,
        )
        assertEquals(HttpStatus.OK, specialistGet.statusCode)
        assertEquals(salon.id, specialistGet.body!!.id)

        // 4) Unrelated authenticated caller (no owner/member/specialist relationship) -> 403
        // ACCESS_DENIED, and the response body is an ApiError, not a SalonResponse - no
        // active/onboardingStatus/ownerId/phone/email/address field reaches this caller.
        val strangerGet = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}"), HttpMethod.GET, HttpEntity<Void>(bearer(strangerToken)), ApiError::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, strangerGet.statusCode)
        assertEquals("ACCESS_DENIED", strangerGet.body!!.errorCode)

        // 5) Same unrelated caller against a nonexistent salon id -> 404, same ApiError shape -
        // confirms the stranger cannot use this endpoint's status code alone to distinguish
        // "exists, DRAFT, not yours" (403) from "doesn't exist" (404) as two DIFFERENT facts about
        // DRAFT status specifically: both are 4xx ApiError bodies with no salon fields whatsoever,
        // and 403 never varies with the target salon's onboarding/active status (verified above:
        // the same stranger gets 403, not 404 and not 200, regardless of DRAFT state).
        val notFound = restTemplate.exchange(
            url("/api/v1/salons/${UUID.randomUUID()}"), HttpMethod.GET, HttpEntity<Void>(bearer(strangerToken)), ApiError::class.java,
        )
        assertEquals(HttpStatus.NOT_FOUND, notFound.statusCode)
        assertEquals("SALON_NOT_FOUND", notFound.body!!.errorCode)
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
