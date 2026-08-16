package ai.rojan.backend.application.audit

import ai.rojan.backend.domain.audit.ActorType
import ai.rojan.backend.domain.audit.AuditActionType
import ai.rojan.backend.domain.audit.AuditEntityType
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class AuditUseCasesTest {

    private val auditEventRepository = InMemoryAuditEventRepository()
    private val recordAuditEventUseCase = RecordAuditEventUseCase(auditEventRepository)

    private fun command(
        salonId: SalonId = SalonId.new(),
        actorId: UserId? = UserId.new(),
        actorType: ActorType = ActorType.OWNER,
        entityId: String = UUID.randomUUID().toString(),
    ) = RecordAuditEventCommand(
        salonId = salonId,
        actorId = actorId,
        actorType = actorType,
        actionType = AuditActionType.MEDIA_UPLOADED,
        entityType = AuditEntityType.MEDIA_ASSET,
        entityId = entityId,
        oldValue = null,
        newValue = """{"mediaType":"GALLERY"}""",
        metadata = null,
    )

    @Test
    fun `recording persists exactly the fields given`() {
        val salonId = SalonId.new()
        val actorId = UserId.new()
        val entityId = UUID.randomUUID().toString()

        val event = recordAuditEventUseCase.execute(command(salonId, actorId, entityId = entityId))

        assertEquals(salonId, event.salonId)
        assertEquals(actorId, event.actorId)
        assertEquals(entityId, event.entityId)
        assertEquals("""{"mediaType":"GALLERY"}""", event.newValue)
    }

    @Test
    fun `a SYSTEM actor with a null actorId is recorded successfully`() {
        val event = recordAuditEventUseCase.execute(command(actorId = null, actorType = ActorType.SYSTEM))
        assertEquals(ActorType.SYSTEM, event.actorType)
    }

    @Test
    fun `a malformed command propagates the domain's own invariant failure`() {
        assertThrows<IllegalArgumentException> {
            recordAuditEventUseCase.execute(command(actorId = null, actorType = ActorType.OWNER))
        }
    }

    @Test
    fun `findBySalonId returns only that salon's events`() {
        val salonA = SalonId.new()
        val salonB = SalonId.new()
        recordAuditEventUseCase.execute(command(salonA))
        recordAuditEventUseCase.execute(command(salonA))
        recordAuditEventUseCase.execute(command(salonB))

        val forA = auditEventRepository.findBySalonId(salonA)

        assertEquals(2, forA.size)
        assertTrue(forA.all { it.salonId == salonA })
    }

    @Test
    fun `findByEntity filters by entity type and id`() {
        val entityId = UUID.randomUUID().toString()
        recordAuditEventUseCase.execute(command(entityId = entityId))
        recordAuditEventUseCase.execute(command())

        val matches = auditEventRepository.findByEntity(AuditEntityType.MEDIA_ASSET, entityId)

        assertEquals(1, matches.size)
        assertEquals(entityId, matches.single().entityId)
    }
}
