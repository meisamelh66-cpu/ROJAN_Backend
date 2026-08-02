package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.common.UserNotFoundException
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.Specialist
import ai.rojan.backend.domain.salon.SpecialistRepository
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository

data class CreateSpecialistCommand(
    val salonId: SalonId,
    val callerId: UserId,
    val userId: UserId?,
    val displayName: String,
    val bio: String?,
    val photoUrl: String?,
)

class CreateSpecialistUseCase(
    private val salonRepository: SalonRepository,
    private val specialistRepository: SpecialistRepository,
    private val userRepository: UserRepository,
) {
    fun execute(command: CreateSpecialistCommand): Specialist {
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        if (salon.ownerId != command.callerId) {
            throw SalonAccessDeniedException(salon.id.value.toString())
        }
        val userId = command.userId?.also {
            userRepository.findById(it) ?: throw UserNotFoundException(it.value.toString())
        }

        val specialist = Specialist.create(
            salonId = salon.id,
            userId = userId,
            displayName = command.displayName,
            bio = command.bio,
            photoUrl = command.photoUrl,
        )
        return specialistRepository.save(specialist)
    }
}
