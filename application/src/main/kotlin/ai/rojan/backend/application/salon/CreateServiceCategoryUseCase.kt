package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.ServiceCategory
import ai.rojan.backend.domain.salon.ServiceCategoryRepository
import ai.rojan.backend.domain.user.UserId

data class CreateServiceCategoryCommand(
    val salonId: SalonId,
    val callerId: UserId,
    val name: String,
    val description: String?,
)

class CreateServiceCategoryUseCase(
    private val salonRepository: SalonRepository,
    private val serviceCategoryRepository: ServiceCategoryRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: CreateServiceCategoryCommand): ServiceCategory {
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        salonPermissionResolver.require(salon.id, command.callerId, Permission.MANAGE_CATALOG)
        val category = ServiceCategory.create(
            salonId = salon.id,
            name = command.name,
            description = command.description,
        )
        return serviceCategoryRepository.save(category)
    }
}
