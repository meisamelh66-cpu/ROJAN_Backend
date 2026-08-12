package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.common.SalonSlugAlreadyTakenException
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.SalonSlugGenerator
import ai.rojan.backend.domain.user.UserId

data class ChangeSalonSlugCommand(val salonId: SalonId, val callerId: UserId, val newSlug: String)

class ChangeSalonSlugUseCase(
    private val salonRepository: SalonRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: ChangeSalonSlugCommand): Salon {
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        salonPermissionResolver.require(salon.id, command.callerId, Permission.MANAGE_SALON)

        val normalized = SalonSlugGenerator.baseSlugFor(command.newSlug)
        if (normalized != salon.slug && salonRepository.existsBySlug(normalized)) {
            throw SalonSlugAlreadyTakenException(normalized)
        }

        salon.changeSlug(normalized)
        return salonRepository.save(salon)
    }
}
