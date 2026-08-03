package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.salon.Branch
import ai.rojan.backend.domain.salon.BranchRepository
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId

data class CreateBranchCommand(
    val salonId: SalonId,
    val callerId: UserId,
    val name: String,
    val address: String,
    val phone: String,
)

class CreateBranchUseCase(
    private val salonRepository: SalonRepository,
    private val branchRepository: BranchRepository,
) {
    fun execute(command: CreateBranchCommand): Branch {
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        if (salon.ownerId != command.callerId) {
            throw SalonAccessDeniedException(salon.id.value.toString())
        }
        val branch = Branch.create(
            salonId = salon.id,
            name = command.name,
            address = command.address,
            phone = command.phone,
        )
        return branchRepository.save(branch)
    }
}
