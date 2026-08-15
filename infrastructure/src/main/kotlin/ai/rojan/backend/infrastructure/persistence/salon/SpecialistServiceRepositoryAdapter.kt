package ai.rojan.backend.infrastructure.persistence.salon

import ai.rojan.backend.domain.salon.ServiceId
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.salon.SpecialistService
import ai.rojan.backend.domain.salon.SpecialistServiceId
import ai.rojan.backend.domain.salon.SpecialistServiceRepository
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class SpecialistServiceRepositoryAdapter(
    private val jpaRepository: SpecialistServiceSpringDataRepository,
) : SpecialistServiceRepository {

    override fun assign(specialistId: SpecialistId, serviceId: ServiceId): SpecialistService {
        val existing = jpaRepository.findBySpecialistIdAndServiceId(specialistId.value, serviceId.value)
        if (existing != null) return existing.toDomain()

        val entity = SpecialistServiceJpaEntity(
            id = SpecialistServiceId.new().value,
            specialistId = specialistId.value,
            serviceId = serviceId.value,
            createdAt = Instant.now(),
        )
        return jpaRepository.save(entity).toDomain()
    }

    override fun remove(specialistId: SpecialistId, serviceId: ServiceId) {
        jpaRepository.deleteBySpecialistIdAndServiceId(specialistId.value, serviceId.value)
    }

    override fun findServiceIdsBySpecialistId(specialistId: SpecialistId): Set<ServiceId> =
        jpaRepository.findBySpecialistId(specialistId.value).map { ServiceId(it.serviceId) }.toSet()

    private fun SpecialistServiceJpaEntity.toDomain(): SpecialistService = SpecialistService.reconstitute(
        id = SpecialistServiceId(id),
        specialistId = SpecialistId(specialistId),
        serviceId = ServiceId(serviceId),
        createdAt = createdAt,
    )
}
