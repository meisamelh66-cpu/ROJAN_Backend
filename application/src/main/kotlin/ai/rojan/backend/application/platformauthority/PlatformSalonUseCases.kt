package ai.rojan.backend.application.platformauthority

import ai.rojan.backend.application.audit.RecordAuditEventCommand
import ai.rojan.backend.application.audit.RecordAuditEventUseCase
import ai.rojan.backend.application.salon.SalonCompletenessResult
import ai.rojan.backend.application.salon.missingSalonActivationRequirements
import ai.rojan.backend.domain.audit.ActorType
import ai.rojan.backend.domain.audit.AuditActionType
import ai.rojan.backend.domain.audit.AuditEntityType
import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.salon.IdentitySlot
import ai.rojan.backend.domain.salon.PlatformSalonFilter
import ai.rojan.backend.domain.salon.PlatformSalonResult
import ai.rojan.backend.domain.salon.PlatformSalonSort
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.ServiceRepository
import ai.rojan.backend.domain.salon.SpecialistRepository
import ai.rojan.backend.domain.schedule.WorkingHoursRepository
import ai.rojan.backend.domain.user.UserId

/**
 * Platform Management API Contract (Salons) - Admin Salon Visibility: the real gap left open after
 * Web's Salon Onboarding/Activation lifecycle shipped - `GET /api/v1/salons` (`SalonController.list`)
 * is, and must remain, the customer/manager-facing "browse ACTIVE salons" contract
 * ([SalonRepository.findAllActive]'s own filter is never touched here or by this use case).
 * Mirrors [ListPlatformManagersUseCase]'s own shape exactly: read-only, so PLATFORM_ADMIN and
 * PLATFORM_REVIEWER have nothing to differ on (same reasoning that use case's own doc comment
 * gives), authorized entirely through [PlatformAuthorizationResolver] - never
 * [ai.rojan.backend.application.salon.SalonPermissionResolver] or any salon membership check, this
 * is platform-wide oversight, not salon-scoped access.
 *
 * Field-for-field, [ListPlatformSalonsQuery] is `docs/backend-requirements/platform-admin-scalability.md`
 * §3/§4's directory contract - the one the Website's `lib/types/platform-salon.ts`/
 * `lib/admin/salon-directory.ts` already model and call once its `salonDirectory` capability flag
 * is turned on.
 */
data class ListPlatformSalonsQuery(
    val callerId: UserId,
    val page: Int,
    val size: Int,
    val filter: PlatformSalonFilter,
    val sort: PlatformSalonSort,
)

class ListPlatformSalonsUseCase(
    private val salonRepository: SalonRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(query: ListPlatformSalonsQuery): PageResult<PlatformSalonResult> {
        platformAuthorization.requirePlatformReviewerOrAdmin(query.callerId)
        return salonRepository.findAllForPlatform(PageRequest(query.page, query.size), query.filter, query.sort)
    }
}

private fun findSalonOrThrow(salonId: SalonId, salonRepository: SalonRepository): Salon =
    salonRepository.findById(salonId) ?: throw SalonNotFoundException(salonId.value.toString())

// ---------------------------------------------------------------------------------------------
// Suspend / Reinstate
// ---------------------------------------------------------------------------------------------

/**
 * Platform Management API Contract (Salons) - Admin Salon Suspend/Reinstate
 * (`docs/backend-requirements/platform-admin-scalability.md` §4, `SALON_STATUS_WRITE`): the real
 * route behind the Website's already-written `salonStatusService.suspend` stub
 * (`lib/admin/backend-gaps.ts`). PLATFORM_ADMIN only - unlike [ListPlatformSalonsUseCase], a write
 * that removes a salon from every customer-facing surface has no reviewer-level equivalent, same
 * "PLATFORM_REVIEWER never mutates" split [DeactivatePlatformManagerUseCase] already establishes.
 *
 * Reuses [Salon.deactivate] - the exact same domain transition an owner's own
 * `DELETE /api/v1/salons/{id}` already causes (`DeactivateSalonUseCase`) - never a second
 * "is this salon soft-deleted" flag. The only two differences from an owner's self-service
 * deactivation: (1) authorization is [PlatformAuthorizationResolver], never
 * [ai.rojan.backend.application.salon.SalonPermissionResolver] - a platform admin need not own or
 * be a member of the salon they are suspending; (2) [reason] is required and recorded as a
 * [AuditActionType.SALON_SUSPENDED] [ai.rojan.backend.domain.audit.AuditEvent] with
 * [ActorType.PLATFORM_AUTHORITY] - an owner's own deactivation is never audited this way (it is
 * [AuditActionType.SALON_UPDATED], same as any other owner self-service change - see that enum
 * value's own doc comment).
 *
 * Deliberately never deletes the salon row, any [ai.rojan.backend.domain.salon.SalonMembership],
 * media, service, specialist, working-hours, or booking data, and never touches the owner's own
 * [ai.rojan.backend.domain.user.User] account - suspending a salon is reversible
 * ([ReinstatePlatformSalonUseCase]) by construction, exactly like every other state-only
 * transition this codebase already models (no hard-delete capability exists anywhere in this
 * domain - see [Salon]'s own module, which has no delete method on [SalonRepository] either).
 */
data class SuspendPlatformSalonCommand(val callerId: UserId, val salonId: SalonId, val reason: String)

class SuspendPlatformSalonUseCase(
    private val salonRepository: SalonRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
    private val recordAuditEventUseCase: RecordAuditEventUseCase,
) {
    fun execute(command: SuspendPlatformSalonCommand): Salon {
        require(command.reason.isNotBlank()) { "A suspend reason is required" }
        val caller = platformAuthorization.requirePlatformAdmin(command.callerId)
        val salon = findSalonOrThrow(command.salonId, salonRepository)
        salon.deactivate()
        val saved = salonRepository.save(salon)
        recordAuditEventUseCase.execute(
            RecordAuditEventCommand(
                salonId = saved.id,
                actorId = caller.id,
                actorType = ActorType.PLATFORM_AUTHORITY,
                actionType = AuditActionType.SALON_SUSPENDED,
                entityType = AuditEntityType.SALON,
                entityId = saved.id.value.toString(),
                metadata = toReasonJson(command.reason),
            ),
        )
        return saved
    }
}

/** The reverse of [SuspendPlatformSalonUseCase] - reuses [Salon.reactivate]. No reason is required (mirrors [ReactivatePlatformManagerUseCase], which also takes none). */
data class ReinstatePlatformSalonCommand(val callerId: UserId, val salonId: SalonId)

class ReinstatePlatformSalonUseCase(
    private val salonRepository: SalonRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
    private val recordAuditEventUseCase: RecordAuditEventUseCase,
) {
    fun execute(command: ReinstatePlatformSalonCommand): Salon {
        val caller = platformAuthorization.requirePlatformAdmin(command.callerId)
        val salon = findSalonOrThrow(command.salonId, salonRepository)
        salon.reactivate()
        val saved = salonRepository.save(salon)
        recordAuditEventUseCase.execute(
            RecordAuditEventCommand(
                salonId = saved.id,
                actorId = caller.id,
                actorType = ActorType.PLATFORM_AUTHORITY,
                actionType = AuditActionType.SALON_REINSTATED,
                entityType = AuditEntityType.SALON,
                entityId = saved.id.value.toString(),
            ),
        )
        return saved
    }
}

/** Minimal, dependency-free JSON construction for a single `reason` string - the audit module's own `metadata` field is a caller-assembled JSON string (see [ai.rojan.backend.domain.audit.AuditEvent]'s own doc comment), and this is the only field this caller ever needs to serialize, so a real JSON library is not pulled into the application layer for it. */
private fun toReasonJson(reason: String): String {
    val escaped = reason.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
    return """{"reason":"$escaped"}"""
}

// ---------------------------------------------------------------------------------------------
// Completeness read (business-profile fields, for the admin edit form to pre-fill)
// ---------------------------------------------------------------------------------------------

/**
 * Platform Management API Contract (Salons) - Admin Salon Edit read side. The real, pre-existing
 * `GET /api/v1/salons/{salonId}/completeness` ([ai.rojan.backend.application.salon.GetSalonCompletenessUseCase])
 * requires [ai.rojan.backend.application.salon.SalonPermissionResolver]'s `MANAGE_SALON` - a platform
 * admin or reviewer is never a member of the salon they're inspecting, so that route 403s for them.
 * This is the platform-authorized read of the exact same fields, gated by
 * [PlatformAuthorizationResolver] instead - read-only, so PLATFORM_ADMIN and PLATFORM_REVIEWER share
 * it, same split [ListPlatformSalonsUseCase] already establishes. Reuses
 * [missingSalonActivationRequirements] and [SalonCompletenessResult] directly (both `internal`/public
 * within this same `application` module) rather than re-deriving either - never a second, possibly
 * drifting notion of "what's missing for activation."
 */
data class GetPlatformSalonCompletenessQuery(val callerId: UserId, val salonId: SalonId)

class GetPlatformSalonCompletenessUseCase(
    private val salonRepository: SalonRepository,
    private val serviceRepository: ServiceRepository,
    private val specialistRepository: SpecialistRepository,
    private val workingHoursRepository: WorkingHoursRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(query: GetPlatformSalonCompletenessQuery): SalonCompletenessResult {
        platformAuthorization.requirePlatformReviewerOrAdmin(query.callerId)
        val salon = findSalonOrThrow(query.salonId, salonRepository)
        val missing = missingSalonActivationRequirements(salon, serviceRepository, specialistRepository, workingHoursRepository)
        return SalonCompletenessResult(salon, missing)
    }
}

// ---------------------------------------------------------------------------------------------
// Edit (identity/contact, profile, business-completeness)
// ---------------------------------------------------------------------------------------------

/**
 * Platform Management API Contract (Salons) - Admin Salon Edit. PLATFORM_ADMIN only
 * (`SALON_STATUS_WRITE`-equivalent write scope - the scalability doc does not name an edit
 * permission separately, so this reuses the same admin-only bar every other platform salon
 * mutation here already enforces). Reuses the same three domain methods the owner-facing
 * `SalonController`/`LocationStep`/`BusinessProfileStep` already call
 * ([Salon.update]/[Salon.updateProfile]/[Salon.updateCompletionProfile]) - never a parallel
 * validation or persistence path. Unlike the owner API's three separate endpoints, this is one
 * combined command: an admin edit form has no reason to split "identity" from "location" from
 * "business profile" into three separate saves the way the onboarding wizard's own multi-step UX
 * does. Every field here is the complete set [Salon.update]/[updateProfile]/
 * [updateCompletionProfile] accept - optional profile/business fields `null` simply mean "leave
 * unchanged" is NOT the semantic here for [latitude]/[longitude]/[city]/the business-profile
 * fields (unlike [ai.rojan.backend.application.salon.UpdateSalonCommand]'s own null-merge
 * contract) - this command always resends the complete current state (the caller/controller is
 * responsible for seeding every field from the real current [Salon] first), matching
 * [Salon.updateCompletionProfile]'s own "always sends the complete current state" contract exactly.
 */
data class UpdatePlatformSalonCommand(
    val callerId: UserId,
    val salonId: SalonId,
    val name: String,
    val description: String?,
    val phone: String,
    val email: String?,
    val address: String,
    val latitude: Double?,
    val longitude: Double?,
    val city: String?,
    val activityStartJalaliYear: Int?,
    val hasInternalExtensions: Boolean,
    val sellsProducts: Boolean?,
    val hasCafe: Boolean?,
    val hasStaffUniform: Boolean?,
    val isNeighborhoodSalon: Boolean?,
    val isCityCenterSalon: Boolean?,
)

class UpdatePlatformSalonUseCase(
    private val salonRepository: SalonRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
    private val recordAuditEventUseCase: RecordAuditEventUseCase,
) {
    fun execute(command: UpdatePlatformSalonCommand): Salon {
        val caller = platformAuthorization.requirePlatformAdmin(command.callerId)
        val salon = findSalonOrThrow(command.salonId, salonRepository)
        salon.update(command.name, command.description, command.phone, command.email, command.address)
        salon.updateProfile(command.latitude, command.longitude, command.city)
        salon.updateCompletionProfile(
            activityStartJalaliYear = command.activityStartJalaliYear,
            hasInternalExtensions = command.hasInternalExtensions,
            sellsProducts = command.sellsProducts,
            hasCafe = command.hasCafe,
            hasStaffUniform = command.hasStaffUniform,
            isNeighborhoodSalon = command.isNeighborhoodSalon,
            isCityCenterSalon = command.isCityCenterSalon,
            primaryContactMembershipId = salon.primaryContactMembershipId,
        )
        val saved = salonRepository.save(salon)
        recordAuditEventUseCase.execute(
            RecordAuditEventCommand(
                salonId = saved.id,
                actorId = caller.id,
                actorType = ActorType.PLATFORM_AUTHORITY,
                actionType = AuditActionType.SALON_UPDATED,
                entityType = AuditEntityType.SALON,
                entityId = saved.id.value.toString(),
            ),
        )
        return saved
    }
}

// ---------------------------------------------------------------------------------------------
// Media removal (logo/cover)
// ---------------------------------------------------------------------------------------------

/**
 * Platform Management API Contract (Salons) - Admin Media Review (Phase 2B "remove/replace an
 * invalid image if the backend supports it"): clears a logo/cover slot only - reuses
 * [Salon.assignIdentityMedia] with a `null` media id, the exact same clearing path
 * `AssignIdentityMediaUseCase` already uses for an owner's own "remove logo" action, just
 * platform-authorized instead of [ai.rojan.backend.application.salon.SalonPermissionResolver]-gated.
 * Deliberately clear-only, not clear-and-upload-a-replacement: this task's own scope boundary
 * ("do not invent APIs") stops short of admitting a platform admin into the real upload pipeline
 * (`UploadMediaUseCase`) - an admin who wants to "replace" an invalid image clears it here, and the
 * owner uploads a new one the normal way. The underlying `MediaAsset` row itself is left untouched
 * (same as `AssignIdentityMediaUseCase` - only the [Salon] identity-slot reference is cleared), so
 * nothing here deletes media storage.
 */
data class RemovePlatformSalonMediaCommand(val callerId: UserId, val salonId: SalonId, val slot: IdentitySlot)

class RemovePlatformSalonMediaUseCase(
    private val salonRepository: SalonRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
    private val recordAuditEventUseCase: RecordAuditEventUseCase,
) {
    fun execute(command: RemovePlatformSalonMediaCommand): Salon {
        val caller = platformAuthorization.requirePlatformAdmin(command.callerId)
        val salon = findSalonOrThrow(command.salonId, salonRepository)
        val previousMediaId = if (command.slot == IdentitySlot.LOGO) salon.logoMediaId else salon.coverMediaId
        salon.assignIdentityMedia(command.slot, null)
        val saved = salonRepository.save(salon)
        if (previousMediaId != null) {
            recordAuditEventUseCase.execute(
                RecordAuditEventCommand(
                    salonId = saved.id,
                    actorId = caller.id,
                    actorType = ActorType.PLATFORM_AUTHORITY,
                    actionType = AuditActionType.MEDIA_DELETED,
                    entityType = AuditEntityType.MEDIA_ASSET,
                    entityId = previousMediaId.value.toString(),
                    metadata = """{"slot":"${command.slot.name}"}""",
                ),
            )
        }
        return saved
    }
}
