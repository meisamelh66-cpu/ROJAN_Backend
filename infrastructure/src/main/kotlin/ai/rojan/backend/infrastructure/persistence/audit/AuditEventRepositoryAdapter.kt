package ai.rojan.backend.infrastructure.persistence.audit

import ai.rojan.backend.domain.audit.AuditEntityType
import ai.rojan.backend.domain.audit.AuditEvent
import ai.rojan.backend.domain.audit.AuditEventId
import ai.rojan.backend.domain.audit.AuditEventRepository
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Repository-pattern adapter: implements the domain [AuditEventRepository]
 * port on top of Spring Data JPA. Deliberately has no update/delete path -
 * [save] always inserts (an [AuditEvent]'s id is never reused, since
 * nothing in the domain layer can produce an existing one to mutate).
 * [entityId] is a generic [String] at the domain layer (§01 of the Phase 4
 * architecture - audit never depends on another domain's typed id) but a
 * `UUID` column here, since every entity type audited today has a UUID
 * primary key underneath; this adapter is the one place that conversion
 * happens.
 */
@Repository
class AuditEventRepositoryAdapter(
    private val jpaRepository: AuditEventSpringDataRepository,
) : AuditEventRepository {

    override fun save(event: AuditEvent): AuditEvent {
        val entity = AuditEventJpaEntity(
            id = event.id.value,
            salonId = event.salonId.value,
            actorId = event.actorId?.value,
            actorType = event.actorType,
            actionType = event.actionType,
            entityType = event.entityType,
            entityId = UUID.fromString(event.entityId),
            oldValue = event.oldValue,
            newValue = event.newValue,
            metadata = event.metadata,
            createdAt = event.createdAt,
        )
        return jpaRepository.save(entity).toDomain()
    }

    override fun findBySalonId(salonId: SalonId): List<AuditEvent> =
        jpaRepository.findBySalonId(salonId.value).map { it.toDomain() }

    override fun findByEntity(entityType: AuditEntityType, entityId: String): List<AuditEvent> =
        jpaRepository.findByEntityTypeAndEntityId(entityType, UUID.fromString(entityId)).map { it.toDomain() }

    private fun AuditEventJpaEntity.toDomain(): AuditEvent = AuditEvent.reconstitute(
        id = AuditEventId(id),
        salonId = SalonId(salonId),
        actorId = actorId?.let { UserId(it) },
        actorType = actorType,
        actionType = actionType,
        entityType = entityType,
        entityId = entityId.toString(),
        oldValue = oldValue,
        newValue = newValue,
        metadata = metadata,
        createdAt = createdAt,
    )
}
