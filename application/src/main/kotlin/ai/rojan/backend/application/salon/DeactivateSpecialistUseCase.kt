package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.common.SpecialistNotFoundException
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.salon.SpecialistRepository
import ai.rojan.backend.domain.user.UserId

data class DeactivateSpecialistCommand(
    val specialistId: SpecialistId,
    val callerId: UserId,
)

class DeactivateSpecialistUseCase(
    private val salonRepository: SalonRepository,
    private val specialistRepository: SpecialistRepository,
) {
    fun execute(command: DeactivateSpecialistCommand) {
        val specialist = specialistRepository.findById(command.specialistId)
            ?: throw SpecialistNotFoundException(command.specialistId.value.toString())
        val salon = salonRepository.findById(specialist.salonId)
            ?: throw SalonNotFoundException(specialist.salonId.value.toString())
        if (salon.ownerId != command.callerId) {
            throw SalonAccessDeniedException(salon.id.value.toString())
        }
        specialist.deactivate()
        specialistRepository.save(specialist)
    }
}
