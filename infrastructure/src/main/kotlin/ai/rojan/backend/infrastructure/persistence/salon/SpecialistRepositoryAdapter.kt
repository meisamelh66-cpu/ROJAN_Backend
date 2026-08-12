package ai.rojan.backend.infrastructure.persistence.salon

import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.Specialist
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.salon.SpecialistRepository
import ai.rojan.backend.domain.user.UserId
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class SpecialistRepositoryAdapter(
    private val jpaRepository: SpecialistSpringDataRepository,
) : SpecialistRepository {

    override fun save(specialist: Specialist): Specialist {
        val entity = jpaRepository.findById(specialist.id.value).orElse(null)
            ?.apply {
                displayName = specialist.displayName
                bio = specialist.bio
                photoUrl = specialist.photoUrl
                active = specialist.active
            }
            ?: SpecialistJpaEntity(
                id = specialist.id.value,
                salonId = specialist.salonId.value,
                userId = specialist.userId?.value,
                displayName = specialist.displayName,
                bio = specialist.bio,
                photoUrl = specialist.photoUrl,
                active = specialist.active,
            )
        return jpaRepository.save(entity).toDomain()
    }

    override fun findById(id: SpecialistId): Specialist? =
        jpaRepository.findById(id.value).orElse(null)?.toDomain()

    override fun findBySalonId(salonId: SalonId): List<Specialist> =
        jpaRepository.findBySalonId(salonId.value).map { it.toDomain() }

    override fun findBySalonIdAndUserId(salonId: SalonId, userId: UserId): Specialist? =
        jpaRepository.findBySalonIdAndUserId(salonId.value, userId.value)?.toDomain()

    override fun findByUserId(userId: UserId): List<Specialist> =
        jpaRepository.findByUserId(userId.value).map { it.toDomain() }

    private fun SpecialistJpaEntity.toDomain(): Specialist = Specialist.reconstitute(
        id = SpecialistId(id),
        salonId = SalonId(salonId),
        userId = userId?.let { UserId(it) },
        displayName = displayName,
        bio = bio,
        photoUrl = photoUrl,
        active = active,
        createdAt = createdAt ?: Instant.EPOCH,
        updatedAt = updatedAt ?: Instant.EPOCH,
    )
}
