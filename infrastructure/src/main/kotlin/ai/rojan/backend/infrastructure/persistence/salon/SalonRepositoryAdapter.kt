package ai.rojan.backend.infrastructure.persistence.salon

import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId
import org.springframework.stereotype.Repository
import java.time.Instant

/** Repository-pattern adapter: implements the domain [SalonRepository] port on top of Spring Data JPA. */
@Repository
class SalonRepositoryAdapter(
    private val jpaRepository: SalonSpringDataRepository,
) : SalonRepository {

    override fun save(salon: Salon): Salon {
        val entity = jpaRepository.findById(salon.id.value).orElse(null)
            ?.apply {
                name = salon.name
                description = salon.description
                phone = salon.phone
                email = salon.email
                address = salon.address
                active = salon.active
            }
            ?: SalonJpaEntity(
                id = salon.id.value,
                ownerId = salon.ownerId.value,
                name = salon.name,
                description = salon.description,
                phone = salon.phone,
                email = salon.email,
                address = salon.address,
                active = salon.active,
            )
        return jpaRepository.save(entity).toDomain()
    }

    override fun findById(id: SalonId): Salon? =
        jpaRepository.findById(id.value).orElse(null)?.toDomain()

    override fun findByOwnerId(ownerId: UserId): List<Salon> =
        jpaRepository.findByOwnerId(ownerId.value).map { it.toDomain() }

    override fun findAllActive(): List<Salon> =
        jpaRepository.findByActiveTrue().map { it.toDomain() }

    private fun SalonJpaEntity.toDomain(): Salon = Salon.reconstitute(
        id = SalonId(id),
        ownerId = UserId(ownerId),
        name = name,
        description = description,
        phone = phone,
        email = email,
        address = address,
        active = active,
        createdAt = createdAt ?: Instant.EPOCH,
        updatedAt = updatedAt ?: Instant.EPOCH,
    )
}
