package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.salon.AssignMembershipRequest
import ai.rojan.backend.api.salon.CreateSalonRequest
import ai.rojan.backend.api.salon.CreateServiceCategoryRequest
import ai.rojan.backend.api.salon.CreateServiceRequest
import ai.rojan.backend.api.salon.CreateSpecialistRequest
import ai.rojan.backend.api.salon.SalonCompletenessResponse
import ai.rojan.backend.api.salon.SalonMembershipResponse
import ai.rojan.backend.api.salon.SalonResponse
import ai.rojan.backend.api.salon.ServiceCategoryResponse
import ai.rojan.backend.api.salon.ServiceResponse
import ai.rojan.backend.api.salon.SpecialistResponse
import ai.rojan.backend.api.salon.UpdateSalonCompletionProfileRequest
import ai.rojan.backend.api.schedule.SetWorkingHoursRequest
import ai.rojan.backend.api.schedule.TimeIntervalDto
import ai.rojan.backend.domain.salon.SalonOnboardingStatus
import ai.rojan.backend.domain.salon.SalonRole
import ai.rojan.backend.domain.user.UserRole
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import java.math.BigDecimal
import java.time.LocalTime
import java.util.UUID

/**
 * ROJAN Salon Completeness - the real HTTP-level coverage `SalonActivationFlowIntegrationTest.kt`
 * has for `/activate` but this feature never got: real Spring context + real (embedded Zonky)
 * Postgres, same pattern as every other file in this directory (duplicated local helpers, no
 * shared base class - this codebase's own established convention, confirmed by inspection of the
 * other integration test files).
 *
 * Central concern this file exists to prove: `GET .../completeness`'s `missingForActivation` and
 * `POST .../activate`'s own rejection reason must never drift - both are backed by the exact same
 * `missingSalonActivationRequirements(...)` function, never a second, independently-computed
 * readiness model. See the last test below.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class SalonCompletenessFlowIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private val restTemplate = TestRestTemplate()

    private fun url(path: String) = "http://localhost:$port$path"

    private fun bearer(token: String) = HttpHeaders().apply { setBearerAuth(token) }

    private fun registerAndLogin(fullName: String): Pair<String, UUID> {
        val email = "completeness.${System.nanoTime()}@example.com"
        val registered = restTemplate.postForEntity(
            url("/api/v1/auth/register"),
            RegisterRequest(email = email, password = "supersecret123", fullName = fullName, role = UserRole.MANAGER),
            UserResponse::class.java,
        )
        val login = restTemplate.postForEntity(
            url("/api/v1/auth/login"),
            LoginRequest(email = email, password = "supersecret123"),
            AuthResponse::class.java,
        )
        return requireNotNull(login.body).accessToken to requireNotNull(registered.body).id
    }

    private fun createSalon(ownerToken: String, name: String): SalonResponse = requireNotNull(
        restTemplate.exchange(
            url("/api/v1/salons"),
            HttpMethod.POST,
            HttpEntity(CreateSalonRequest(name, null, "+1 555 0100", null, "1 Main St"), bearer(ownerToken)),
            SalonResponse::class.java,
        ).body,
    )

    private fun createCategory(ownerToken: String, salonId: UUID, name: String): ServiceCategoryResponse = requireNotNull(
        restTemplate.exchange(
            url("/api/v1/salons/$salonId/categories"),
            HttpMethod.POST,
            HttpEntity(CreateServiceCategoryRequest(name, null), bearer(ownerToken)),
            ServiceCategoryResponse::class.java,
        ).body,
    )

    private fun createService(ownerToken: String, salonId: UUID, categoryId: UUID, name: String): ServiceResponse = requireNotNull(
        restTemplate.exchange(
            url("/api/v1/salons/$salonId/categories/$categoryId/services"),
            HttpMethod.POST,
            HttpEntity(CreateServiceRequest(name, null, 30, BigDecimal("25.00")), bearer(ownerToken)),
            ServiceResponse::class.java,
        ).body,
    )

    private fun createSpecialist(ownerToken: String, salonId: UUID, displayName: String): SpecialistResponse = requireNotNull(
        restTemplate.exchange(
            url("/api/v1/salons/$salonId/specialists"),
            HttpMethod.POST,
            HttpEntity(CreateSpecialistRequest(null, displayName, null, null, "+989120000001", "Stylist"), bearer(ownerToken)),
            SpecialistResponse::class.java,
        ).body,
    )

    private fun setMondayWorkingHours(ownerToken: String, salonId: UUID) {
        restTemplate.exchange(
            url("/api/v1/salons/$salonId/working-hours/MONDAY"), HttpMethod.PUT,
            HttpEntity(SetWorkingHoursRequest(listOf(TimeIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)))), bearer(ownerToken)),
            String::class.java,
        )
    }

    /** Satisfies all three real activation readiness requirements - the same three-call sequence `SalonActivationFlowIntegrationTest` itself uses. */
    private fun satisfyActivationReadiness(ownerToken: String, salonId: UUID) {
        val hair = createCategory(ownerToken, salonId, "Hair")
        createService(ownerToken, salonId, hair.id, "Women's Haircut")
        createSpecialist(ownerToken, salonId, "Mariam Karimi")
        setMondayWorkingHours(ownerToken, salonId)
    }

    private fun assignMembership(ownerToken: String, salonId: UUID, userId: UUID, role: SalonRole): SalonMembershipResponse = requireNotNull(
        restTemplate.exchange(
            url("/api/v1/salons/$salonId/members/$userId"), HttpMethod.PUT,
            HttpEntity(AssignMembershipRequest(role), bearer(ownerToken)),
            SalonMembershipResponse::class.java,
        ).body,
    )

    private fun getCompleteness(token: String, salonId: UUID) = restTemplate.exchange(
        url("/api/v1/salons/$salonId/completeness"), HttpMethod.GET,
        HttpEntity<Void>(bearer(token)), SalonCompletenessResponse::class.java,
    )

    private fun activate(token: String, salonId: UUID) = restTemplate.exchange(
        url("/api/v1/salons/$salonId/activate"), HttpMethod.POST,
        HttpEntity<Void>(bearer(token)), SalonResponse::class.java,
    )

    @Test
    fun `GET completeness for a brand-new salon returns 200 with every field null-or-default and the same three activation blockers activation itself enforces`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")

        val response = getCompleteness(ownerToken, salon.id)

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = requireNotNull(response.body)
        assertEquals(salon.id, body.salonId)
        assertNull(body.activityStartJalaliYear)
        assertFalse(body.hasInternalExtensions)
        assertNull(body.sellsProducts)
        assertNull(body.hasCafe)
        assertNull(body.hasStaffUniform)
        assertNull(body.isNeighborhoodSalon)
        assertNull(body.isCityCenterSalon)
        assertNull(body.primaryContactMembershipId)

        assertEquals(3, body.missingForActivation.size)
        assertTrue(body.missingForActivation.any { it.contains("active service") })
        assertTrue(body.missingForActivation.any { it.contains("active specialist") })
        assertTrue(body.missingForActivation.any { it.contains("working-hours") })
        // Completeness fields are trackable but never activation blockers - regression-proofed here
        // at the real HTTP contract level, not only in the use-case unit test.
        assertTrue(body.missingForActivation.none { it.contains("activity") || it.contains("year") })
        assertTrue(body.missingForActivation.none { it.contains("contact") })
    }

    @Test
    fun `PUT completeness with the full Website contract payload echoes back exactly what was submitted, and a later GET reflects the same persisted round-trip values`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val (_, receptionUserId) = registerAndLogin("Niloofar Reception")
        val membership = assignMembership(ownerToken, salon.id, receptionUserId, SalonRole.RECEPTIONIST)

        val request = UpdateSalonCompletionProfileRequest(
            activityStartJalaliYear = 1399,
            hasInternalExtensions = true,
            sellsProducts = true,
            hasCafe = false,
            hasStaffUniform = null,
            isNeighborhoodSalon = true,
            isCityCenterSalon = false,
            primaryContactMembershipId = membership.id,
        )
        val putResponse = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/completeness"), HttpMethod.PUT,
            HttpEntity(request, bearer(ownerToken)), SalonCompletenessResponse::class.java,
        )

        assertEquals(HttpStatus.OK, putResponse.statusCode)
        val putBody = requireNotNull(putResponse.body)
        assertEquals(1399, putBody.activityStartJalaliYear)
        assertTrue(putBody.hasInternalExtensions)
        assertEquals(true, putBody.sellsProducts)
        assertEquals(false, putBody.hasCafe)
        assertNull(putBody.hasStaffUniform)
        assertEquals(true, putBody.isNeighborhoodSalon)
        assertEquals(false, putBody.isCityCenterSalon)
        assertEquals(membership.id, putBody.primaryContactMembershipId)

        val getResponse = getCompleteness(ownerToken, salon.id)
        assertEquals(HttpStatus.OK, getResponse.statusCode)
        val getBody = requireNotNull(getResponse.body)
        assertEquals(1399, getBody.activityStartJalaliYear)
        assertTrue(getBody.hasInternalExtensions)
        assertEquals(true, getBody.sellsProducts)
        assertEquals(false, getBody.hasCafe)
        assertNull(getBody.hasStaffUniform)
        assertEquals(true, getBody.isNeighborhoodSalon)
        assertEquals(false, getBody.isCityCenterSalon)
        assertEquals(membership.id, getBody.primaryContactMembershipId)
    }

    @Test
    fun `unauthenticated GET and PUT completeness are both rejected with 401`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")

        val getResponse = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/completeness"), HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders()), String::class.java,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, getResponse.statusCode)

        val putResponse = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/completeness"), HttpMethod.PUT,
            HttpEntity(UpdateSalonCompletionProfileRequest(null, false, null, null, null, null, null, null), HttpHeaders()),
            String::class.java,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, putResponse.statusCode)
    }

    @Test
    fun `an authenticated caller without MANAGE_SALON on this salon gets 403 for both GET and PUT`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val (strangerToken, _) = registerAndLogin("Uninvolved Stranger")

        val getResponse = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/completeness"), HttpMethod.GET,
            HttpEntity<Void>(bearer(strangerToken)), String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, getResponse.statusCode)

        val putResponse = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/completeness"), HttpMethod.PUT,
            HttpEntity(UpdateSalonCompletionProfileRequest(null, false, null, null, null, null, null, null), bearer(strangerToken)),
            String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, putResponse.statusCode)
    }

    @Test
    fun `GET completeness for an unknown salonId returns 404`() {
        val (callerToken, _) = registerAndLogin("Sara Ahmadi")

        val response = restTemplate.exchange(
            url("/api/v1/salons/${UUID.randomUUID()}/completeness"), HttpMethod.GET,
            HttpEntity<Void>(bearer(callerToken)), String::class.java,
        )

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertTrue(response.body!!.contains("SALON_NOT_FOUND"))
    }

    @Test
    fun `GET completeness's missingForActivation and POST activate's own rejection can never drift - the same three gates, before and after satisfying them`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")

        val beforeCompleteness = getCompleteness(ownerToken, salon.id)
        assertEquals(HttpStatus.OK, beforeCompleteness.statusCode)
        val reportedMissing = requireNotNull(beforeCompleteness.body).missingForActivation
        assertEquals(3, reportedMissing.size)

        val prematureActivation = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/activate"), HttpMethod.POST,
            HttpEntity<Void>(bearer(ownerToken)), String::class.java,
        )
        assertEquals(HttpStatus.CONFLICT, prematureActivation.statusCode)
        assertTrue(prematureActivation.body!!.contains("SALON_NOT_READY_FOR_ACTIVATION"))
        // Every reason GET /completeness just reported must appear verbatim in the real activation
        // rejection - the two surfaces read the exact same missingSalonActivationRequirements(...)
        // list, never two independently-maintained readiness computations.
        reportedMissing.forEach { reason -> assertTrue(prematureActivation.body!!.contains(reason)) }

        satisfyActivationReadiness(ownerToken, salon.id)

        val afterCompleteness = getCompleteness(ownerToken, salon.id)
        assertEquals(HttpStatus.OK, afterCompleteness.statusCode)
        assertTrue(requireNotNull(afterCompleteness.body).missingForActivation.isEmpty())

        val activation = activate(ownerToken, salon.id)
        assertEquals(HttpStatus.OK, activation.statusCode)
        assertEquals(SalonOnboardingStatus.ACTIVE, requireNotNull(activation.body).onboardingStatus)
    }
}
