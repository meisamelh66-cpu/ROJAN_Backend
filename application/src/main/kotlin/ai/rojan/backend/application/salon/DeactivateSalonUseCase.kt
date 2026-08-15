package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId

data class DeactivateSalonCommand(
    val salonId: SalonId,
    val callerId: UserId,
)

class DeactivateSalonUseCase(
    private val salonRepository: SalonRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: DeactivateSalonCommand) {
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        salonPermissionResolver.require(salon.id, command.callerId, Permission.MANAGE_SALON)
        salon.deactivate()
        salonRepository.save(salon)
    }
}
