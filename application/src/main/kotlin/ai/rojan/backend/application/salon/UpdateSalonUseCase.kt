package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId

data class UpdateSalonCommand(
    val salonId: SalonId,
    val callerId: UserId,
    val name: String,
    val description: String?,
    val phone: String,
    val email: String?,
    val address: String,
)

class UpdateSalonUseCase(
    private val salonRepository: SalonRepository,
) {
    fun execute(command: UpdateSalonCommand): Salon {
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        if (salon.ownerId != command.callerId) {
            throw SalonAccessDeniedException(salon.id.value.toString())
        }
        salon.update(
            name = command.name,
            description = command.description,
            phone = command.phone,
            email = command.email,
            address = command.address,
        )
        return salonRepository.save(salon)
    }
}
