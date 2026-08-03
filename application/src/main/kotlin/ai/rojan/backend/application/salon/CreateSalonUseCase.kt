package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId

data class CreateSalonCommand(
    val ownerId: UserId,
    val name: String,
    val description: String?,
    val phone: String,
    val email: String?,
    val address: String,
)

class CreateSalonUseCase(
    private val salonRepository: SalonRepository,
) {
    fun execute(command: CreateSalonCommand): Salon {
        val salon = Salon.create(
            ownerId = command.ownerId,
            name = command.name,
            description = command.description,
            phone = command.phone,
            email = command.email,
            address = command.address,
        )
        return salonRepository.save(salon)
    }
}
