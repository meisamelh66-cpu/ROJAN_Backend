package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.common.ServiceCategoryNotFoundException
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.Service
import ai.rojan.backend.domain.salon.ServiceCategoryId
import ai.rojan.backend.domain.salon.ServiceCategoryRepository
import ai.rojan.backend.domain.salon.ServiceRepository
import ai.rojan.backend.domain.user.UserId
import java.math.BigDecimal

data class CreateServiceCommand(
    val salonId: SalonId,
    val categoryId: ServiceCategoryId,
    val callerId: UserId,
    val name: String,
    val description: String?,
    val durationMinutes: Int,
    val price: BigDecimal,
)

class CreateServiceUseCase(
    private val salonRepository: SalonRepository,
    private val serviceCategoryRepository: ServiceCategoryRepository,
    private val serviceRepository: ServiceRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: CreateServiceCommand): Service {
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        salonPermissionResolver.require(salon.id, command.callerId, Permission.MANAGE_CATALOG)
        val category = serviceCategoryRepository.findById(command.categoryId)
            ?.takeIf { it.salonId == salon.id }
            ?: throw ServiceCategoryNotFoundException(command.categoryId.value.toString())

        val service = Service.create(
            salonId = salon.id,
            categoryId = category.id,
            name = command.name,
            description = command.description,
            durationMinutes = command.durationMinutes,
            price = command.price,
        )
        return serviceRepository.save(service)
    }
}
