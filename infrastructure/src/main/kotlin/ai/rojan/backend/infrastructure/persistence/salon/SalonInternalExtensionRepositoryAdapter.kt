package ai.rojan.backend.infrastructure.persistence.salon

import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonInternalExtension
import ai.rojan.backend.domain.salon.SalonInternalExtensionId
import ai.rojan.backend.domain.salon.SalonInternalExtensionRepository
import org.springframework.stereotype.Repository
import java.time.Instant

/** Repository-pattern adapter: implements the domain [SalonInternalExtensionRepository] port on top of Spring Data JPA. */
@Repository
class SalonInternalExtensionRepositoryAdapter(
    private val jpaRepository: SalonInternalExtensionSpringDataRepository,
) : SalonInternalExtensionRepository {

    override fun save(extension: SalonInternalExtension): SalonInternalExtension {
        val entity = jpaRepository.findById(extension.id.value).orElse(null)
            ?.apply {
                titleType = extension.titleType
                title = extension.title
                extensionNumber = extension.extensionNumber
            }
            ?: SalonInternalExtensionJpaEntity(
                id = extension.id.value,
                salonId = extension.salonId.value,
                titleType = extension.titleType,
                title = extension.title,
                extensionNumber = extension.extensionNumber,
            )
        return jpaRepository.save(entity).toDomain()
    }

    override fun findBySalonId(salonId: SalonId): List<SalonInternalExtension> =
        jpaRepository.findBySalonId(salonId.value).map { it.toDomain() }

    override fun findByIdAndSalonId(id: SalonInternalExtensionId, salonId: SalonId): SalonInternalExtension? =
        jpaRepository.findByIdAndSalonId(id.value, salonId.value)?.toDomain()

    override fun deleteByIdAndSalonId(id: SalonInternalExtensionId, salonId: SalonId) {
        jpaRepository.deleteByIdAndSalonId(id.value, salonId.value)
    }

    private fun SalonInternalExtensionJpaEntity.toDomain(): SalonInternalExtension = SalonInternalExtension.reconstitute(
        id = SalonInternalExtensionId(id),
        salonId = SalonId(salonId),
        titleType = titleType,
        title = title,
        extensionNumber = extensionNumber,
        createdAt = createdAt ?: Instant.EPOCH,
    )
}
