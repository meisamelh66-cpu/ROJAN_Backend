package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.common.SpecialistNotFoundException
import ai.rojan.backend.domain.salon.Permission
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
    val mobileNumber: PhoneNumber?,
    val specialty: String?,
)

class UpdateSpecialistUseCase(
    private val salonRepository: SalonRepository,
    private val specialistRepository: SpecialistRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: UpdateSpecialistCommand): Specialist {
        val specialist = specialistRepository.findById(command.specialistId)
            ?: throw SpecialistNotFoundException(command.specialistId.value.toString())
        val salon = salonRepository.findById(specialist.salonId)
            ?: throw SalonNotFoundException(specialist.salonId.value.toString())
        salonPermissionResolver.require(salon.id, command.callerId, Permission.MANAGE_STAFF)
        specialist.update(
            displayName = command.displayName,
            bio = command.bio,
            photoUrl = command.photoUrl,
            mobileNumber = command.mobileNumber,
            specialty = command.specialty,
        )
        return specialistRepository.save(specialist)
    }
}
