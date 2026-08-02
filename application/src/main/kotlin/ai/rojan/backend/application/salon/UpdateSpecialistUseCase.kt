package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.common.SpecialistNotFoundException
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.Specialist
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.salon.SpecialistRepository
import ai.rojan.backend.domain.user.UserId

data class UpdateSpecialistCommand(
    val specialistId: SpecialistId,
    val callerId: UserId,
    val displayName: String,
    val bio: String?,
    val photoUrl: String?,
)

class UpdateSpecialistUseCase(
    private val salonRepository: SalonRepository,
    private val specialistRepository: SpecialistRepository,
) {
    fun execute(command: UpdateSpecialistCommand): Specialist {
        val specialist = specialistRepository.findById(command.specialistId)
            ?: throw SpecialistNotFoundException(command.specialistId.value.toString())
        val salon = salonRepository.findById(specialist.salonId)
            ?: throw SalonNotFoundException(specialist.salonId.value.toString())
        if (salon.ownerId != command.callerId) {
            throw SalonAccessDeniedException(salon.id.value.toString())
        }
        specialist.update(displayName = command.displayName, bio = command.bio, photoUrl = command.photoUrl)
        return specialistRepository.save(specialist)
    }
}
