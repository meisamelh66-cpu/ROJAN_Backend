package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.invite.InviteDetailsResponse
import ai.rojan.backend.api.invite.SalonInviteAcceptedResponse
import ai.rojan.backend.api.salon.CreateSalonInviteRequest
import ai.rojan.backend.api.salon.CreateSalonRequest
import ai.rojan.backend.api.salon.CreateServiceCategoryRequest
import ai.rojan.backend.api.salon.SalonInviteResponse
import ai.rojan.backend.api.salon.SalonResponse
import ai.rojan.backend.application.salon.CreateSalonInviteCommand
import ai.rojan.backend.application.salon.CreateSalonInviteUseCase
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonInviteStatus
import ai.rojan.backend.domain.salon.SalonRole
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRole
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import java.util.UUID

/**
 * ROJAN Real Salon Invite System - exercises the `SalonInvite` lifecycle
 * (CREATED/ACCEPTED/EXPIRED/REVOKED) and the six new endpoints end-to-end
 * against the real HTTP layer and embedded Postgres, same pattern as every
 * other file in this directory. This is the Reception/Manager QR-invite
 * mechanism from the approved "Real Salon Pilot Onboarding Architecture"
 * doc - Phase 1, backend only (no Customer QR, no app UI).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class RealSalonInviteIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var createSalonInviteUseCase: CreateSalonInviteUseCase

    private val restTemplate = TestRestTemplate()

    private fun url(path: String) = "http://localhost:$port$path"

    private fun bearer(token: String) = HttpHeaders().apply { setBearerAuth(token) }

    private fun registerAndLogin(fullName: String): Pair<String, UUID> {
        val email = "invite.${System.nanoTime()}@example.com"
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

    private fun createInvite(ownerToken: String, salonId: UUID, role: SalonRole): SalonInviteResponse = requireNotNull(
        restTemplate.exchange(
            url("/api/v1/salons/$salonId/invites"),
            HttpMethod.POST,
            HttpEntity(CreateSalonInviteRequest(role), bearer(ownerToken)),
            SalonInviteResponse::class.java,
        ).body,
    )

    @Test
    fun `owner can create an invite`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")

        val invite = createInvite(ownerToken, salon.id, SalonRole.RECEPTIONIST)

        assertEquals(salon.id, invite.salonId)
        assertEquals(SalonRole.RECEPTIONIST, invite.role)
        assertEquals(SalonInviteStatus.CREATED, invite.status)
        assertTrue(invite.token.isNotBlank())
    }

    @Test
    fun `a non-owner cannot create an invite`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val (strangerToken, _) = registerAndLogin("Random Stranger")

        val response = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/invites"),
            HttpMethod.POST,
            HttpEntity(CreateSalonInviteRequest(SalonRole.RECEPTIONIST), bearer(strangerToken)),
            String::class.java,
        )

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `a reception invite grants exactly the RECEPTIONIST membership on accept`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val invite = createInvite(ownerToken, salon.id, SalonRole.RECEPTIONIST)

        val details = restTemplate.getForEntity(url("/api/v1/invites/${invite.token}"), InviteDetailsResponse::class.java)
        assertEquals(HttpStatus.OK, details.statusCode)
        assertEquals(salon.name, details.body!!.salonName)
        assertEquals(SalonRole.RECEPTIONIST, details.body!!.role)

        val (receptionToken, _) = registerAndLogin("Nazanin Reception")
        val accepted = restTemplate.exchange(
            url("/api/v1/invites/${invite.token}/accept"), HttpMethod.POST, HttpEntity<Void>(bearer(receptionToken)), SalonInviteAcceptedResponse::class.java,
        )
        assertEquals(HttpStatus.OK, accepted.statusCode)
        assertEquals(salon.id, accepted.body!!.salonId)
        assertEquals(SalonRole.RECEPTIONIST, accepted.body!!.role)

        // Receptionist can now manage bookings but not the catalog - the exact permission set SalonRole.RECEPTIONIST grants.
        val categoryAttempt = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/categories"), HttpMethod.POST,
            HttpEntity(CreateServiceCategoryRequest("Hair", null), bearer(receptionToken)), String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, categoryAttempt.statusCode)

        val bookingsView = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/bookings"), HttpMethod.GET, HttpEntity<Void>(bearer(receptionToken)), String::class.java,
        )
        assertEquals(HttpStatus.OK, bookingsView.statusCode)
    }

    @Test
    fun `a manager invite grants exactly the MANAGER membership on accept`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val invite = createInvite(ownerToken, salon.id, SalonRole.MANAGER)

        val (managerToken, _) = registerAndLogin("Mariam Manager")
        val accepted = restTemplate.exchange(
            url("/api/v1/invites/${invite.token}/accept"), HttpMethod.POST, HttpEntity<Void>(bearer(managerToken)), SalonInviteAcceptedResponse::class.java,
        )
        assertEquals(HttpStatus.OK, accepted.statusCode)
        assertEquals(SalonRole.MANAGER, accepted.body!!.role)

        // Manager can manage the catalog...
        val category = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/categories"), HttpMethod.POST,
            HttpEntity(CreateServiceCategoryRequest("Hair", null), bearer(managerToken)), String::class.java,
        )
        assertEquals(HttpStatus.CREATED, category.statusCode)

        // ...but never salon settings or membership management (MANAGE_SALON/MANAGE_MEMBERSHIP stay owner-only).
        val inviteAttempt = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/invites"), HttpMethod.POST,
            HttpEntity(CreateSalonInviteRequest(SalonRole.MANAGER), bearer(managerToken)), String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, inviteAttempt.statusCode)
    }

    @Test
    fun `an invalid token is rejected identically at lookup and accept`() {
        val (someToken, _) = registerAndLogin("Someone")

        val lookup = restTemplate.getForEntity(url("/api/v1/invites/does-not-exist"), String::class.java)
        assertEquals(HttpStatus.NOT_FOUND, lookup.statusCode)
        assertTrue(lookup.body!!.contains("SALON_INVITE_NOT_FOUND"))

        val accept = restTemplate.exchange(
            url("/api/v1/invites/does-not-exist/accept"), HttpMethod.POST, HttpEntity<Void>(bearer(someToken)), String::class.java,
        )
        assertEquals(HttpStatus.NOT_FOUND, accept.statusCode)
        assertTrue(accept.body!!.contains("SALON_INVITE_NOT_FOUND"))
    }

    @Test
    fun `an expired token is rejected`() {
        val (ownerToken, ownerId) = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")

        // Mints an already-expired invite through the real use case (not a raw DB insert) -
        // the only way to test true expiry without waiting out a real 48h TTL. The 1ms override
        // is a test-only CreateSalonInviteCommand field, never reachable through the public API.
        val expired = createSalonInviteUseCase.execute(
            CreateSalonInviteCommand(SalonId(salon.id), UserId(ownerId), SalonRole.RECEPTIONIST, ttl = Duration.ofMillis(1)),
        )
        Thread.sleep(10)

        val (staffToken, _) = registerAndLogin("Late Scanner")
        val lookup = restTemplate.getForEntity(url("/api/v1/invites/${expired.token}"), String::class.java)
        assertEquals(HttpStatus.NOT_FOUND, lookup.statusCode)

        val accept = restTemplate.exchange(
            url("/api/v1/invites/${expired.token}/accept"), HttpMethod.POST, HttpEntity<Void>(bearer(staffToken)), String::class.java,
        )
        assertEquals(HttpStatus.NOT_FOUND, accept.statusCode)
    }

    @Test
    fun `double accept of the same token is rejected`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val invite = createInvite(ownerToken, salon.id, SalonRole.RECEPTIONIST)

        val (firstToken, _) = registerAndLogin("First Scanner")
        val first = restTemplate.exchange(
            url("/api/v1/invites/${invite.token}/accept"), HttpMethod.POST, HttpEntity<Void>(bearer(firstToken)), SalonInviteAcceptedResponse::class.java,
        )
        assertEquals(HttpStatus.OK, first.statusCode)

        val (secondToken, _) = registerAndLogin("Second Scanner")
        val second = restTemplate.exchange(
            url("/api/v1/invites/${invite.token}/accept"), HttpMethod.POST, HttpEntity<Void>(bearer(secondToken)), String::class.java,
        )
        assertEquals(HttpStatus.NOT_FOUND, second.statusCode)
        assertTrue(second.body!!.contains("SALON_INVITE_NOT_FOUND"))
    }

    @Test
    fun `revoking or generating a QR for an invite through the wrong salon path is rejected - tenant isolation`() {
        val (ownerAToken, _) = registerAndLogin("Sara Ahmadi")
        val salonA = createSalon(ownerAToken, "Salon A")
        val inviteA = createInvite(ownerAToken, salonA.id, SalonRole.RECEPTIONIST)

        val (ownerBToken, _) = registerAndLogin("Velvet Owner")
        val salonB = createSalon(ownerBToken, "Salon B")

        // Salon B's owner cannot revoke or QR-generate Salon A's invite by guessing its id under their own salon path.
        val crossTenantRevoke = restTemplate.exchange(
            url("/api/v1/salons/${salonB.id}/invites/${inviteA.id}"), HttpMethod.DELETE, HttpEntity<Void>(bearer(ownerBToken)), String::class.java,
        )
        assertEquals(HttpStatus.NOT_FOUND, crossTenantRevoke.statusCode)

        val crossTenantQr = restTemplate.exchange(
            url("/api/v1/salons/${salonB.id}/invites/${inviteA.id}/qr-code"), HttpMethod.GET, HttpEntity<Void>(bearer(ownerBToken)), String::class.java,
        )
        assertEquals(HttpStatus.NOT_FOUND, crossTenantQr.statusCode)

        // The invite is untouched and still acceptable - the rejected cross-tenant attempt had no side effect.
        assertEquals(
            SalonInviteStatus.CREATED,
            restTemplate.exchange(
                url("/api/v1/salons/${salonA.id}/invites"), HttpMethod.GET, HttpEntity<Void>(bearer(ownerAToken)),
                Array<SalonInviteResponse>::class.java,
            ).body!!.first { it.id == inviteA.id }.status,
        )
    }

    @Test
    fun `accepting an invite at one salon grants no access at an unrelated salon`() {
        val (ownerAToken, _) = registerAndLogin("Sara Ahmadi")
        val salonA = createSalon(ownerAToken, "Salon A")
        val inviteA = createInvite(ownerAToken, salonA.id, SalonRole.MANAGER)

        val (ownerBToken, _) = registerAndLogin("Velvet Owner")
        val salonB = createSalon(ownerBToken, "Salon B")

        val (staffToken, _) = registerAndLogin("Cross Tenant Staff")
        restTemplate.exchange(
            url("/api/v1/invites/${inviteA.token}/accept"), HttpMethod.POST, HttpEntity<Void>(bearer(staffToken)), SalonInviteAcceptedResponse::class.java,
        )

        // Granted MANAGER at salon A...
        val salonACatalog = restTemplate.exchange(
            url("/api/v1/salons/${salonA.id}/categories"), HttpMethod.POST,
            HttpEntity(CreateServiceCategoryRequest("Hair", null), bearer(staffToken)), String::class.java,
        )
        assertEquals(HttpStatus.CREATED, salonACatalog.statusCode)

        // ...but zero access at salon B, which this invite never mentioned.
        val salonBCatalog = restTemplate.exchange(
            url("/api/v1/salons/${salonB.id}/categories"), HttpMethod.POST,
            HttpEntity(CreateServiceCategoryRequest("Nails", null), bearer(staffToken)), String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, salonBCatalog.statusCode)
    }

    @Test
    fun `owner can list and revoke invites`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val invite = createInvite(ownerToken, salon.id, SalonRole.RECEPTIONIST)

        val listed = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/invites"), HttpMethod.GET, HttpEntity<Void>(bearer(ownerToken)), Array<SalonInviteResponse>::class.java,
        )
        assertEquals(HttpStatus.OK, listed.statusCode)
        assertTrue(listed.body!!.any { it.id == invite.id })

        val revoke = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/invites/${invite.id}"), HttpMethod.DELETE, HttpEntity<Void>(bearer(ownerToken)), Void::class.java,
        )
        assertEquals(HttpStatus.NO_CONTENT, revoke.statusCode)

        val (staffToken, _) = registerAndLogin("Too Late Reception")
        val acceptAfterRevoke = restTemplate.exchange(
            url("/api/v1/invites/${invite.token}/accept"), HttpMethod.POST, HttpEntity<Void>(bearer(staffToken)), String::class.java,
        )
        assertEquals(HttpStatus.NOT_FOUND, acceptAfterRevoke.statusCode)
    }

    @Test
    fun `owner can download a scannable QR code PNG for an invite`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val invite = createInvite(ownerToken, salon.id, SalonRole.RECEPTIONIST)

        val qr = restTemplate.exchange(
            url("/api/v1/salons/${salon.id}/invites/${invite.id}/qr-code"), HttpMethod.GET, HttpEntity<Void>(bearer(ownerToken)), ByteArray::class.java,
        )

        assertEquals(HttpStatus.OK, qr.statusCode)
        assertEquals(MediaType.IMAGE_PNG, qr.headers.contentType)
        assertTrue(qr.body!!.isNotEmpty())
    }
}
