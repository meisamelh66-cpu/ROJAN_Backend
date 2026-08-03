package ai.rojan.backend.domain.salon

interface ServiceRepository {
    fun save(service: Service): Service
    fun findById(id: ServiceId): Service?
    fun findByCategoryId(categoryId: ServiceCategoryId): List<Service>
    fun findBySalonId(salonId: SalonId): List<Service>
}
