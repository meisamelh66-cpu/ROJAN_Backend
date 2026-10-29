package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.Service
import ai.rojan.backend.domain.salon.ServiceCategoryId
import ai.rojan.backend.domain.salon.ServiceCategoryRepository
import ai.rojan.backend.domain.salon.ServiceRepository

/**
 * Issue-020 fix: a deactivated category must behave the same as a nonexistent one for public
 * reads. Previously `PublicSalonController.services()` only filtered on the service's own
 * `active` flag, so a category already hidden from the category list (`categories()` filters on
 * `it.active`) still had its real, active services directly fetchable by a known/guessed
 * `categoryId` - a nested-consistency gap in the catalog contract. No service record is touched
 * and no historical booking reference changes; this is a read-boundary guard only.
 */
class GetPublicCategoryServicesUseCase(
    private val serviceCategoryRepository: ServiceCategoryRepository,
    private val serviceRepository: ServiceRepository,
) {
    fun execute(salonId: SalonId, categoryId: ServiceCategoryId): List<Service> {
        val category = serviceCategoryRepository.findById(categoryId)
            ?.takeIf { it.salonId == salonId && it.active }
            ?: return emptyList()
        return serviceRepository.findByCategoryId(category.id)
            .filter { it.salonId == salonId && it.active }
    }
}
