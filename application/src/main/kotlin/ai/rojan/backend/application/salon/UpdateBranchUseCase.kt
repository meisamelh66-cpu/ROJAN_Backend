package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.BranchNotFoundException
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.salon.Branch
import ai.rojan.backend.domain.salon.BranchId
import ai.rojan.backend.domain.salon.BranchRepository
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId

data class UpdateBranchCommand(
    val branchId: BranchId,
    val callerId: UserId,
    val name: String,
    val address: String,
    val phone: String,
)

class UpdateBranchUseCase(
    private val salonRepository: SalonRepository,
    private val branchRepository: BranchRepository,
) {
    fun execute(command: UpdateBranchCommand): Branch {
        val branch = branchRepository.findById(command.branchId)
            ?: throw BranchNotFoundException(command.branchId.value.toString())
        val salon = salonRepository.findById(branch.salonId)
            ?: throw SalonNotFoundException(branch.salonId.value.toString())
        if (salon.ownerId != command.callerId) {
            throw SalonAccessDeniedException(salon.id.value.toString())
        }
        branch.update(name = command.name, address = command.address, phone = command.phone)
        return branchRepository.save(branch)
    }
}
