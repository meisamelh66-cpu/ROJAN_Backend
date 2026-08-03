package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.common.ServiceNotFoundException
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
) {
    fun execute(command: DeactivateServiceCommand) {
        val service = serviceRepository.findById(command.serviceId)
            ?: throw ServiceNotFoundException(command.serviceId.value.toString())
        val salon = salonRepository.findById(service.salonId)
            ?: throw SalonNotFoundException(service.salonId.value.toString())
        if (salon.ownerId != command.callerId) {
            throw SalonAccessDeniedException(salon.id.value.toString())
        }
        service.deactivate()
        serviceRepository.save(service)
    }
}
