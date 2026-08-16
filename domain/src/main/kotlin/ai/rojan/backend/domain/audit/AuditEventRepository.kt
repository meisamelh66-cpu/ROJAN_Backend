package ai.rojan.backend.domain.audit

import ai.rojan.backend.domain.salon.SalonId

/**
 * Deliberately declares no `update`/`delete` - "no update, no delete" is
 * this interface never defining either method, not a comment asking a
 * caller not to.
 */
interface AuditEventRepository {
    fun save(event: AuditEvent): AuditEvent

    /** Tenant-scoped by construction, same discipline as every other repository in this codebase. */
    fun findBySalonId(salonId: SalonId): List<AuditEvent>

    fun findByEntity(entityType: AuditEntityType, entityId: String): List<AuditEvent>
}
