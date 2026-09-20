package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.common.SalonNotReadyForActivationException
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.ServiceRepository
import ai.rojan.backend.domain.salon.SpecialistRepository
import ai.rojan.backend.domain.schedule.WorkingHoursRepository
import ai.rojan.backend.domain.user.UserId

data class ActivateSalonCommand(val salonId: SalonId, val callerId: UserId)

/**
 * The single source of truth for "is this salon ready to go ACTIVE" - shared
 * by [ActivateSalonUseCase] (which enforces it) and [GetSalonCompletenessUseCase]
 * (which only reports it), so the two can never drift apart.
 *
 * Deliberately never checks [Salon.rojanVerified]/[ai.rojan.backend.domain.verification.SalonVerification]/
 * hygiene certificates/geographic verification/reviewer scores - ROJAN
 * verification is not a second activation gate (Salon Completeness +
 * Verification architecture, confirmed decision).
 *
 * Every Salon Completeness field ([Salon.activityStartJalaliYear]/[Salon.hasInternalExtensions]/
 * [Salon.sellsProducts]/[Salon.hasCafe]/[Salon.hasStaffUniform]/[Salon.isNeighborhoodSalon]/
 * [Salon.isCityCenterSalon]/[Salon.primaryContactMembershipId]) is deliberately answer-whenever, never
 * gated here - a real, previously-shipped regression (found during Phase 5's full bootstrap
 * integration-test run: making [Salon.activityStartJalaliYear]/[Salon.primaryContactMembershipId]
 * mandatory broke activation for every salon that hadn't completed that profile, pre-existing or
 * brand new) established this must stay a strict three-requirement gate - services/specialists/hours
 * only, exactly as before Salon Completeness existed.
 */
internal fun missingSalonActivationRequirements(
    salon: Salon,
    serviceRepository: ServiceRepository,
    specialistRepository: SpecialistRepository,
    workingHoursRepository: WorkingHoursRepository,
): List<String> {
    val missing = mutableListOf<String>()
    if (serviceRepository.findBySalonId(salon.id).none { it.active }) missing += "at least one active service"
    if (specialistRepository.findBySalonId(salon.id).none { it.active }) missing += "at least one active specialist"
    if (workingHoursRepository.findBySalonId(salon.id).isEmpty()) missing += "at least one configured working-hours day"
    return missing
}

/**
 * Owner-only ([Permission.MANAGE_SALON]). `Salon.activate()` only guards the
 * state transition itself - the actual readiness check is cross-aggregate
 * (services/specialists/working-hours all live in their own repositories),
 * so it belongs here, not on the [Salon] entity.
 */
class ActivateSalonUseCase(
    private val salonRepository: SalonRepository,
    private val serviceRepository: ServiceRepository,
    private val specialistRepository: SpecialistRepository,
    private val workingHoursRepository: WorkingHoursRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: ActivateSalonCommand): Salon {
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        salonPermissionResolver.require(salon.id, command.callerId, Permission.MANAGE_SALON)

        val missing = missingSalonActivationRequirements(salon, serviceRepository, specialistRepository, workingHoursRepository)
        if (missing.isNotEmpty()) {
            throw SalonNotReadyForActivationException(salon.id.value.toString(), missing)
        }

        salon.activate()
        return salonRepository.save(salon)
    }
}
