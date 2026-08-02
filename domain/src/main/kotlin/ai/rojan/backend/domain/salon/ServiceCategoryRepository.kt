package ai.rojan.backend.domain.salon

interface ServiceCategoryRepository {
    fun save(category: ServiceCategory): ServiceCategory
    fun findById(id: ServiceCategoryId): ServiceCategory?
    fun findBySalonId(salonId: SalonId): List<ServiceCategory>
}
