package ai.rojan.backend.domain.audit

import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class AuditEventTest {

    @Test
    fun `create builds a fully-populated event for a non-SYSTEM actor`() {
        val salonId = SalonId.new()
        val actorId = UserId.new()

        val event = AuditEvent.create(
            salonId = salonId,
            actorId = actorId,
            actorType = ActorType.OWNER,
            actionType = AuditActionType.SALON_CREATED,
            entityType = AuditEntityType.SALON,
            entityId = salonId.value.toString(),
            oldValue = null,
            newValue = """{"name":"Glow Salon"}""",
            metadata = null,
        )

        assertEquals(salonId, event.salonId)
        assertEquals(actorId, event.actorId)
        assertEquals(ActorType.OWNER, event.actorType)
        assertEquals(AuditActionType.SALON_CREATED, event.actionType)
        assertEquals(AuditEntityType.SALON, event.entityType)
        assertEquals("""{"name":"Glow Salon"}""", event.newValue)
        assertNull(event.oldValue)
    }

    @Test
    fun `create allows a SYSTEM actor with a null actorId`() {
        val salonId = SalonId.new()

        val event = AuditEvent.create(
            salonId = salonId,
            actorId = null,
            actorType = ActorType.SYSTEM,
            actionType = AuditActionType.VERIFICATION_APPROVED,
            entityType = AuditEntityType.SALON_VERIFICATION,
            entityId = UUID.randomUUID().toString(),
            oldValue = null,
            newValue = null,
            metadata = null,
        )

        assertNull(event.actorId)
        assertEquals(ActorType.SYSTEM, event.actorType)
    }

    @Test
    fun `create rejects a SYSTEM actor with a non-null actorId`() {
        assertThrows(IllegalArgumentException::class.java) {
            AuditEvent.create(
                salonId = SalonId.new(),
                actorId = UserId.new(),
                actorType = ActorType.SYSTEM,
                actionType = AuditActionType.MEDIA_DELETED,
                entityType = AuditEntityType.MEDIA_ASSET,
                entityId = UUID.randomUUID().toString(),
                oldValue = null,
                newValue = null,
                metadata = null,
            )
        }
    }

    @Test
    fun `create rejects a non-SYSTEM actor with a null actorId`() {
        assertThrows(IllegalArgumentException::class.java) {
            AuditEvent.create(
                salonId = SalonId.new(),
                actorId = null,
                actorType = ActorType.OWNER,
                actionType = AuditActionType.MEDIA_UPLOADED,
                entityType = AuditEntityType.MEDIA_ASSET,
                entityId = UUID.randomUUID().toString(),
                oldValue = null,
                newValue = null,
                metadata = null,
            )
        }
    }

    @Test
    fun `reconstitute round-trips every field unchanged`() {
        val id = AuditEventId.new()
        val salonId = SalonId.new()
        val actorId = UserId.new()
        val entityId = UUID.randomUUID().toString()
        val createdAt = Instant.parse("2026-01-01T00:00:00Z")

        val event = AuditEvent.reconstitute(
            id = id,
            salonId = salonId,
            actorId = actorId,
            actorType = ActorType.MEMBER,
            actionType = AuditActionType.DOCUMENT_ATTACHED,
            entityType = AuditEntityType.SALON_DOCUMENT,
            entityId = entityId,
            oldValue = null,
            newValue = """{"documentType":"LICENSE"}""",
            metadata = """{"source":"manager-app"}""",
            createdAt = createdAt,
        )

        assertEquals(id, event.id)
        assertEquals(salonId, event.salonId)
        assertEquals(actorId, event.actorId)
        assertEquals(ActorType.MEMBER, event.actorType)
        assertEquals(AuditActionType.DOCUMENT_ATTACHED, event.actionType)
        assertEquals(AuditEntityType.SALON_DOCUMENT, event.entityType)
        assertEquals(entityId, event.entityId)
        assertEquals("""{"documentType":"LICENSE"}""", event.newValue)
        assertEquals("""{"source":"manager-app"}""", event.metadata)
        assertEquals(createdAt, event.createdAt)
    }
}
