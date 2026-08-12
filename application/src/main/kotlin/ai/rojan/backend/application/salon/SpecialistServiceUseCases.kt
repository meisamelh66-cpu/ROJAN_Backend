package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.ServiceNotFoundException
import ai.rojan.backend.domain.common.SpecialistNotFoundException
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.Service
import ai.rojan.backend.domain.salon.ServiceId
import ai.rojan.backend.domain.salon.ServiceRepository
import ai.rojan.backend.domain.salon.Specialist
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.salon.SpecialistRepository
import ai.rojan.backend.domain.salon.SpecialistService
import ai.rojan.backend.domain.salon.SpecialistServiceRepository
import ai.rojan.backend.domain.user.UserId

data class AssignServiceToSpecialistCommand(
    val salonId: SalonId,
    val specialistId: SpecialistId,
    val serviceId: ServiceId,
    val callerId: UserId,
)

/**
 * Owner/manager ([Permission.MANAGE_STAFF]) for any specialist, or the
 * specialist themself ([Permission.MANAGE_SCHEDULE_OWN], own record only -
 * this is the "own... service eligibility" self-service the pilot plan
 * describes). Re-validates on every call (rather than trusting a cached
 * relationship) that both the specialist and the service belong to
 * [salonId] - the concrete guarantee that a salon can never assign a
 * competitor's service to its own specialist, or vice versa, even given a
 * valid-but-foreign [ServiceId]/[SpecialistId].
 */
class AssignServiceToSpecialistUseCase(
    private val specialistRepository: SpecialistRepository,
    private val serviceRepository: ServiceRepository,
    private val specialistServiceRepository: SpecialistServiceRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: AssignServiceToSpecialistCommand): SpecialistService {
        val (specialist, service) = validate(specialistRepository, serviceRepository, salonPermissionResolver, command.salonId, command.specialistId, command.serviceId, command.callerId)
        return specialistServiceRepository.assign(specialist.id, service.id)
    }
}

data class RemoveServiceFromSpecialistCommand(
    val salonId: SalonId,
    val specialistId: SpecialistId,
    val serviceId: ServiceId,
    val callerId: UserId,
)

class RemoveServiceFromSpecialistUseCase(
    private val specialistRepository: SpecialistRepository,
    private val serviceRepository: ServiceRepository,
    private val specialistServiceRepository: SpecialistServiceRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: RemoveServiceFromSpecialistCommand) {
        val (specialist, service) = validate(specialistRepository, serviceRepository, salonPermissionResolver, command.salonId, command.specialistId, command.serviceId, command.callerId)
        specialistServiceRepository.remove(specialist.id, service.id)
    }
}

private fun validate(
    specialistRepository: SpecialistRepository,
    serviceRepository: ServiceRepository,
    salonPermissionResolver: SalonPermissionResolver,
    salonId: SalonId,
    specialistId: SpecialistId,
    serviceId: ServiceId,
    callerId: UserId,
): Pair<Specialist, Service> {
    val specialist = specialistRepository.findById(specialistId)
        ?.takeIf { it.salonId == salonId }
        ?: throw SpecialistNotFoundException(specialistId.value.toString())

    salonPermissionResolver.requireCanManageSpecialist(specialist, callerId, Permission.MANAGE_STAFF)

    val service = serviceRepository.findById(serviceId)
        ?.takeIf { it.salonId == salonId }
        ?: throw ServiceNotFoundException(serviceId.value.toString())

    return specialist to service
}
