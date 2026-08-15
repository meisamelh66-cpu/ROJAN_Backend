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

        val missing = mutableListOf<String>()
        if (serviceRepository.findBySalonId(salon.id).none { it.active }) missing += "at least one active service"
        if (specialistRepository.findBySalonId(salon.id).none { it.active }) missing += "at least one active specialist"
        if (workingHoursRepository.findBySalonId(salon.id).isEmpty()) missing += "at least one configured working-hours day"
        if (missing.isNotEmpty()) {
            throw SalonNotReadyForActivationException(salon.id.value.toString(), missing)
        }

        salon.activate()
        return salonRepository.save(salon)
    }
}
