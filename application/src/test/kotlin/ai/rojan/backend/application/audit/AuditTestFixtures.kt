package ai.rojan.backend.application.audit

import ai.rojan.backend.domain.audit.AuditEntityType
import ai.rojan.backend.domain.audit.AuditEvent
import ai.rojan.backend.domain.audit.AuditEventId
import ai.rojan.backend.domain.audit.AuditEventRepository
import ai.rojan.backend.domain.salon.SalonId

/** Mirrors [ai.rojan.backend.application.verification.InMemorySalonVerificationRepository]'s style. */
internal class InMemoryAuditEventRepository : AuditEventRepository {
    private val store = mutableMapOf<AuditEventId, AuditEvent>()

    override fun save(event: AuditEvent): AuditEvent = event.also { store[it.id] = it }

    override fun findBySalonId(salonId: SalonId): List<AuditEvent> =
        store.values.filter { it.salonId == salonId }

    override fun findByEntity(entityType: AuditEntityType, entityId: String): List<AuditEvent> =
        store.values.filter { it.entityType == entityType && it.entityId == entityId }
}
