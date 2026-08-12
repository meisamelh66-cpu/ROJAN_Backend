package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.common.ServiceNotFoundException
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.ServiceId
import ai.rojan.backend.domain.salon.ServiceRepository
import ai.rojan.backend.domain.user.UserId

data class DeactivateServiceCommand(
    val serviceId: ServiceId,
    val callerId: UserId,
)

class DeactivateServiceUseCase(
    private val salonRepository: SalonRepository,
    private val serviceRepository: ServiceRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: DeactivateServiceCommand) {
        val service = serviceRepository.findById(command.serviceId)
            ?: throw ServiceNotFoundException(command.serviceId.value.toString())
        val salon = salonRepository.findById(service.salonId)
            ?: throw SalonNotFoundException(service.salonId.value.toString())
        salonPermissionResolver.require(salon.id, command.callerId, Permission.MANAGE_CATALOG)
        service.deactivate()
        serviceRepository.save(service)
    }
}
