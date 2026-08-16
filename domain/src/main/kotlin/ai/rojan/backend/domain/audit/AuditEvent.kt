package ai.rojan.backend.domain.audit

import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId
import java.time.Instant
import java.util.UUID

@JvmInline
value class AuditEventId(val value: UUID) {
    companion object {
        fun new(): AuditEventId = AuditEventId(UUID.randomUUID())
    }
}

enum class ActorType { OWNER, MEMBER, PLATFORM_AUTHORITY, SYSTEM }

enum class AuditActionType {
    SALON_CREATED,
    SALON_UPDATED,
    MEDIA_UPLOADED,
    MEDIA_DELETED,
    DOCUMENT_ATTACHED,
    DOCUMENT_REMOVED,
    VERIFICATION_SUBMITTED,
    VERIFICATION_APPROVED,
    VERIFICATION_REJECTED,
}

enum class AuditEntityType { SALON, MEDIA_ASSET, SALON_DOCUMENT, SALON_VERIFICATION }

/**
 * A single, write-once fact about a salon-lifecycle change - unlike every
 * other aggregate this project has shipped ([ai.rojan.backend.domain.document.SalonDocument],
 * [ai.rojan.backend.domain.verification.SalonVerification]), this one has
 * no state-transition method and no `var` field, because it has nothing to
 * transition. "No update, no delete" is therefore not a rule callers must
 * remember - [AuditEventRepository] simply never declares either method.
 *
 * [entityId]/[entityType] deliberately reference the changed aggregate
 * generically (a raw id string plus a small closed enum) rather than a
 * typed [ai.rojan.backend.domain.media.MediaAssetId]/
 * [ai.rojan.backend.domain.document.SalonDocumentId]/
 * [ai.rojan.backend.domain.verification.SalonVerificationId] union - this
 * package imports nothing from `domain.media`, `domain.document`, or
 * `domain.verification`, which is what keeps every future domain able to
 * publish an audit event without audit ever depending back on it.
 * [salonId]/[actorId] stay typed as a deliberate, narrow exception:
 * `domain.salon`/`domain.user` are already the shared kernel every other
 * domain here depends on.
 *
 * [oldValue]/[newValue]/[metadata] are caller-assembled JSON strings, never
 * a serialized entity - there is no snapshot helper anywhere in this type.
 * A caller decides exactly which fields leave its own module; this class
 * has no way to see more than it's handed (Phase 4 architecture §05).
 */
class AuditEvent private constructor(
    val id: AuditEventId,
    val salonId: SalonId,
    val actorId: UserId?,
    val actorType: ActorType,
    val actionType: AuditActionType,
    val entityType: AuditEntityType,
    val entityId: String,
    val oldValue: String?,
    val newValue: String?,
    val metadata: String?,
    val createdAt: Instant,
) {
    companion object {
        fun create(
            salonId: SalonId,
            actorId: UserId?,
            actorType: ActorType,
            actionType: AuditActionType,
            entityType: AuditEntityType,
            entityId: String,
            oldValue: String?,
            newValue: String?,
            metadata: String?,
        ): AuditEvent {
            require((actorType == ActorType.SYSTEM) == (actorId == null)) {
                "A SYSTEM-authored event must not carry an actorId, and every other actor type must"
            }
            return AuditEvent(
                id = AuditEventId.new(),
                salonId = salonId,
                actorId = actorId,
                actorType = actorType,
                actionType = actionType,
                entityType = entityType,
                entityId = entityId,
                oldValue = oldValue,
                newValue = newValue,
                metadata = metadata,
                createdAt = Instant.now(),
            )
        }

        fun reconstitute(
            id: AuditEventId,
            salonId: SalonId,
            actorId: UserId?,
            actorType: ActorType,
            actionType: AuditActionType,
            entityType: AuditEntityType,
            entityId: String,
            oldValue: String?,
            newValue: String?,
            metadata: String?,
            createdAt: Instant,
        ): AuditEvent = AuditEvent(
            id, salonId, actorId, actorType, actionType, entityType, entityId, oldValue, newValue, metadata, createdAt,
        )
    }
}
