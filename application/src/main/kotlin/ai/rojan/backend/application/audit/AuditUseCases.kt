package ai.rojan.backend.application.audit

import ai.rojan.backend.domain.audit.ActorType
import ai.rojan.backend.domain.audit.AuditActionType
import ai.rojan.backend.domain.audit.AuditEntityType
import ai.rojan.backend.domain.audit.AuditEvent
import ai.rojan.backend.domain.audit.AuditEventRepository
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId

data class RecordAuditEventCommand(
    val salonId: SalonId,
    val actorId: UserId?,
    val actorType: ActorType,
    val actionType: AuditActionType,
    val entityType: AuditEntityType,
    val entityId: String,
    val oldValue: String? = null,
    val newValue: String? = null,
    val metadata: String? = null,
)

/**
 * The only way an [AuditEvent] row can ever be created - not exposed to
 * any controller this phase (Phase 4 restriction: no public audit API).
 * Deliberately does not re-validate salon existence or re-check
 * permission: by the time a caller invokes this, it has already resolved
 * the salon and passed its own permission check before letting its state
 * change through. Re-checking here would be the "parallel authorization"
 * pattern Phases 2 and 3 both rejected.
 *
 * No caller exists yet - retrofitting the seven already-shipped use cases
 * that would call this (`CreateSalonUseCase`, `UpdateSalonUseCase`,
 * `UploadMediaUseCase`, `DeleteMediaUseCase`, `AttachDocumentUseCase`,
 * `DeleteDocumentUseCase`, `SubmitVerificationUseCase`) is explicitly out
 * of scope for this phase.
 */
class RecordAuditEventUseCase(
    private val auditEventRepository: AuditEventRepository,
) {
    fun execute(command: RecordAuditEventCommand): AuditEvent {
        val event = AuditEvent.create(
            salonId = command.salonId,
            actorId = command.actorId,
            actorType = command.actorType,
            actionType = command.actionType,
            entityType = command.entityType,
            entityId = command.entityId,
            oldValue = command.oldValue,
            newValue = command.newValue,
            metadata = command.metadata,
        )
        return auditEventRepository.save(event)
    }
}
