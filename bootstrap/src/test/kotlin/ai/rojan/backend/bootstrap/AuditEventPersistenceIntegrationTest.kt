package ai.rojan.backend.bootstrap

import ai.rojan.backend.api.auth.AuthResponse
import ai.rojan.backend.api.auth.LoginRequest
import ai.rojan.backend.api.auth.RegisterRequest
import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.salon.CreateSalonRequest
import ai.rojan.backend.api.salon.SalonResponse
import ai.rojan.backend.application.audit.RecordAuditEventCommand
import ai.rojan.backend.application.audit.RecordAuditEventUseCase
import ai.rojan.backend.domain.audit.ActorType
import ai.rojan.backend.domain.audit.AuditActionType
import ai.rojan.backend.domain.audit.AuditEntityType
import ai.rojan.backend.domain.audit.AuditEventRepository
import ai.rojan.backend.domain.salon.SalonId
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
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * Salon Audit History Foundation (Phase 4) - real embedded Postgres, but
 * unlike every other integration test in this directory, driven directly
 * through the [RecordAuditEventUseCase]/[AuditEventRepository] beans
 * rather than HTTP: there is no controller to call (Phase 4 restriction -
 * no public audit API). Proves the full stack - Spring wiring, JPA
 * mapping, the V21 migration, and tenant-scoped queries - works together
 * even with zero real callers wired in yet.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
class AuditEventPersistenceIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var recordAuditEventUseCase: RecordAuditEventUseCase

    @Autowired
    private lateinit var auditEventRepository: AuditEventRepository

    private val restTemplate = TestRestTemplate()

    private fun url(path: String) = "http://localhost:$port$path"

    private fun bearer(token: String) = HttpHeaders().apply { setBearerAuth(token) }

    private fun registerAndLogin(fullName: String): Pair<String, UUID> {
        val email = "audit.${System.nanoTime()}@example.com"
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

    @Test
    fun `a recorded event round-trips through real Postgres with every field intact`() {
        val (ownerToken, ownerId) = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val entityId = UUID.randomUUID().toString()

        val recorded = recordAuditEventUseCase.execute(
            RecordAuditEventCommand(
                salonId = SalonId(salon.id),
                actorId = UserId(ownerId),
                actorType = ActorType.OWNER,
                actionType = AuditActionType.SALON_UPDATED,
                entityType = AuditEntityType.SALON,
                entityId = entityId,
                oldValue = """{"name":"Old Name"}""",
                newValue = """{"name":"Rojan Beauty Studio"}""",
                metadata = """{"source":"integration-test"}""",
            ),
        )

        val bySalon = auditEventRepository.findBySalonId(SalonId(salon.id))
        assertEquals(1, bySalon.size)
        val fetched = bySalon.single()
        assertEquals(recorded.id, fetched.id)
        assertEquals(UserId(ownerId), fetched.actorId)
        assertEquals(ActorType.OWNER, fetched.actorType)
        assertEquals(AuditActionType.SALON_UPDATED, fetched.actionType)
        assertEquals(AuditEntityType.SALON, fetched.entityType)
        assertEquals(entityId, fetched.entityId)
        assertEquals("""{"name":"Old Name"}""", fetched.oldValue)
        assertEquals("""{"name":"Rojan Beauty Studio"}""", fetched.newValue)
        assertEquals("""{"source":"integration-test"}""", fetched.metadata)

        val byEntity = auditEventRepository.findByEntity(AuditEntityType.SALON, entityId)
        assertEquals(1, byEntity.size)
        assertEquals(recorded.id, byEntity.single().id)
    }

    @Test
    fun `a SYSTEM-authored event persists with a null actor`() {
        val (ownerToken, _) = registerAndLogin("Sara Ahmadi")
        val salon = createSalon(ownerToken, "Rojan Beauty Studio")
        val entityId = UUID.randomUUID().toString()

        recordAuditEventUseCase.execute(
            RecordAuditEventCommand(
                salonId = SalonId(salon.id),
                actorId = null,
                actorType = ActorType.SYSTEM,
                actionType = AuditActionType.VERIFICATION_APPROVED,
                entityType = AuditEntityType.SALON_VERIFICATION,
                entityId = entityId,
                oldValue = null,
                newValue = null,
                metadata = null,
            ),
        )

        val fetched = auditEventRepository.findByEntity(AuditEntityType.SALON_VERIFICATION, entityId).single()
        assertNull(fetched.actorId)
        assertEquals(ActorType.SYSTEM, fetched.actorType)
    }

    @Test
    fun `findBySalonId never returns another salon's events`() {
        val (ownerToken, ownerId) = registerAndLogin("Sara Ahmadi")
        val salonA = createSalon(ownerToken, "Salon A")
        val salonB = createSalon(ownerToken, "Salon B")

        recordAuditEventUseCase.execute(
            RecordAuditEventCommand(
                salonId = SalonId(salonA.id), actorId = UserId(ownerId), actorType = ActorType.OWNER,
                actionType = AuditActionType.SALON_CREATED, entityType = AuditEntityType.SALON,
                entityId = salonA.id.toString(), oldValue = null, newValue = null, metadata = null,
            ),
        )
        recordAuditEventUseCase.execute(
            RecordAuditEventCommand(
                salonId = SalonId(salonB.id), actorId = UserId(ownerId), actorType = ActorType.OWNER,
                actionType = AuditActionType.SALON_CREATED, entityType = AuditEntityType.SALON,
                entityId = salonB.id.toString(), oldValue = null, newValue = null, metadata = null,
            ),
        )

        val forA = auditEventRepository.findBySalonId(SalonId(salonA.id))

        assertEquals(1, forA.size)
        assertTrue(forA.all { it.salonId == SalonId(salonA.id) })
    }
}
