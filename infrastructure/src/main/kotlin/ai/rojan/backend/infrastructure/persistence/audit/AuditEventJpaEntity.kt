package ai.rojan.backend.infrastructure.persistence.audit

import ai.rojan.backend.domain.audit.ActorType
import ai.rojan.backend.domain.audit.AuditActionType
import ai.rojan.backend.domain.audit.AuditEntityType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Persistence model for [ai.rojan.backend.domain.audit.AuditEvent] -
 * deliberately no `@EntityListeners(AuditingEntityListener::class)` and no
 * `updated_at`: nothing about this row ever changes after insert, so there
 * is nothing for an update-auditing listener to do. Every field is a `val`
 * - this class has no setter, mirroring the domain type's own immutability.
 */
@Entity
@Table(name = "audit_events")
class AuditEventJpaEntity(
    @Id
    val id: UUID,

    @Column(name = "salon_id", nullable = false)
    val salonId: UUID,

    @Column(name = "actor_id", nullable = true)
    val actorId: UUID?,

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20)
    val actorType: ActorType,

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 40)
    val actionType: AuditActionType,

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 20)
    val entityType: AuditEntityType,

    @Column(name = "entity_id", nullable = false)
    val entityId: UUID,

    @Column(name = "old_value", nullable = true)
    val oldValue: String?,

    @Column(name = "new_value", nullable = true)
    val newValue: String?,

    @Column(name = "metadata", nullable = true)
    val metadata: String?,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
)
