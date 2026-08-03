package ai.rojan.backend.infrastructure.persistence.salon

import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.Service
import ai.rojan.backend.domain.salon.ServiceCategoryId
import ai.rojan.backend.domain.salon.ServiceId
import ai.rojan.backend.domain.salon.ServiceRepository
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class ServiceRepositoryAdapter(
    private val jpaRepository: ServiceSpringDataRepository,
) : ServiceRepository {

    override fun save(service: Service): Service {
        val entity = jpaRepository.findById(service.id.value).orElse(null)
            ?.apply {
                name = service.name
                description = service.description
                durationMinutes = service.durationMinutes
                price = service.price
                active = service.active
            }
            ?: ServiceJpaEntity(
                id = service.id.value,
                salonId = service.salonId.value,
                categoryId = service.categoryId.value,
                name = service.name,
                description = service.description,
                durationMinutes = service.durationMinutes,
                price = service.price,
                active = service.active,
            )
        return jpaRepository.save(entity).toDomain()
    }

    override fun findById(id: ServiceId): Service? =
        jpaRepository.findById(id.value).orElse(null)?.toDomain()

    override fun findByCategoryId(categoryId: ServiceCategoryId): List<Service> =
        jpaRepository.findByCategoryId(categoryId.value).map { it.toDomain() }

    override fun findBySalonId(salonId: SalonId): List<Service> =
        jpaRepository.findBySalonId(salonId.value).map { it.toDomain() }

    private fun ServiceJpaEntity.toDomain(): Service = Service.reconstitute(
        id = ServiceId(id),
        salonId = SalonId(salonId),
        categoryId = ServiceCategoryId(categoryId),
        name = name,
        description = description,
        durationMinutes = durationMinutes,
        price = price,
        active = active,
        createdAt = createdAt ?: Instant.EPOCH,
        updatedAt = updatedAt ?: Instant.EPOCH,
    )
}
