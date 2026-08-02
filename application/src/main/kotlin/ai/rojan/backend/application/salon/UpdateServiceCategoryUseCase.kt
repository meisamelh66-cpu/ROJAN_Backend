package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.common.ServiceCategoryNotFoundException
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.ServiceCategory
import ai.rojan.backend.domain.salon.ServiceCategoryId
import ai.rojan.backend.domain.salon.ServiceCategoryRepository
import ai.rojan.backend.domain.user.UserId

data class UpdateServiceCategoryCommand(
    val categoryId: ServiceCategoryId,
    val callerId: UserId,
    val name: String,
    val description: String?,
)

class UpdateServiceCategoryUseCase(
    private val salonRepository: SalonRepository,
    private val serviceCategoryRepository: ServiceCategoryRepository,
) {
    fun execute(command: UpdateServiceCategoryCommand): ServiceCategory {
        val category = serviceCategoryRepository.findById(command.categoryId)
            ?: throw ServiceCategoryNotFoundException(command.categoryId.value.toString())
        val salon = salonRepository.findById(category.salonId)
            ?: throw SalonNotFoundException(category.salonId.value.toString())
        if (salon.ownerId != command.callerId) {
            throw SalonAccessDeniedException(salon.id.value.toString())
        }
        category.update(name = command.name, description = command.description)
        return serviceCategoryRepository.save(category)
    }
}
