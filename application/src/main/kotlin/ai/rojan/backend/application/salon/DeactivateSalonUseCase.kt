package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId

data class DeactivateSalonCommand(
    val salonId: SalonId,
    val callerId: UserId,
)

class DeactivateSalonUseCase(
    private val salonRepository: SalonRepository,
) {
    fun execute(command: DeactivateSalonCommand) {
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        if (salon.ownerId != command.callerId) {
            throw SalonAccessDeniedException(salon.id.value.toString())
        }
        salon.deactivate()
        salonRepository.save(salon)
    }
}
