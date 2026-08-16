package ai.rojan.backend.infrastructure.persistence.audit

import ai.rojan.backend.domain.audit.AuditEntityType
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AuditEventSpringDataRepository : JpaRepository<AuditEventJpaEntity, UUID> {
    fun findBySalonId(salonId: UUID): List<AuditEventJpaEntity>
    fun findByEntityTypeAndEntityId(entityType: AuditEntityType, entityId: UUID): List<AuditEventJpaEntity>
}
