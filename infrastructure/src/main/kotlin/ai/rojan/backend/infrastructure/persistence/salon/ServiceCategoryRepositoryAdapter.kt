package ai.rojan.backend.infrastructure.persistence.salon

import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.ServiceCategory
import ai.rojan.backend.domain.salon.ServiceCategoryId
import ai.rojan.backend.domain.salon.ServiceCategoryRepository
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class ServiceCategoryRepositoryAdapter(
    private val jpaRepository: ServiceCategorySpringDataRepository,
) : ServiceCategoryRepository {

    override fun save(category: ServiceCategory): ServiceCategory {
        val entity = jpaRepository.findById(category.id.value).orElse(null)
            ?.apply {
                name = category.name
                description = category.description
                active = category.active
            }
            ?: ServiceCategoryJpaEntity(
                id = category.id.value,
                salonId = category.salonId.value,
                name = category.name,
                description = category.description,
                active = category.active,
            )
        return jpaRepository.save(entity).toDomain()
    }

    override fun findById(id: ServiceCategoryId): ServiceCategory? =
        jpaRepository.findById(id.value).orElse(null)?.toDomain()

    override fun findBySalonId(salonId: SalonId): List<ServiceCategory> =
        jpaRepository.findBySalonId(salonId.value).map { it.toDomain() }

    private fun ServiceCategoryJpaEntity.toDomain(): ServiceCategory = ServiceCategory.reconstitute(
        id = ServiceCategoryId(id),
        salonId = SalonId(salonId),
        name = name,
        description = description,
        active = active,
        createdAt = createdAt ?: Instant.EPOCH,
        updatedAt = updatedAt ?: Instant.EPOCH,
    )
}
