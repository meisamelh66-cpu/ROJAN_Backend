package ai.rojan.backend.infrastructure.persistence.salon

import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonMembership
import ai.rojan.backend.domain.salon.SalonMembershipId
import ai.rojan.backend.domain.salon.SalonMembershipRepository
import ai.rojan.backend.domain.salon.SalonRole
import ai.rojan.backend.domain.user.UserId
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class SalonMembershipRepositoryAdapter(
    private val jpaRepository: SalonMembershipSpringDataRepository,
) : SalonMembershipRepository {

    override fun assign(salonId: SalonId, userId: UserId, role: SalonRole): SalonMembership {
        val entity = jpaRepository.findBySalonIdAndUserId(salonId.value, userId.value)
            ?.apply { this.role = role.name }
            ?: SalonMembershipJpaEntity(
                id = SalonMembershipId.new().value,
                salonId = salonId.value,
                userId = userId.value,
                role = role.name,
            )
        return jpaRepository.save(entity).toDomain()
    }

    override fun remove(salonId: SalonId, userId: UserId) {
        jpaRepository.deleteBySalonIdAndUserId(salonId.value, userId.value)
    }

    override fun findBySalonIdAndUserId(salonId: SalonId, userId: UserId): SalonMembership? =
        jpaRepository.findBySalonIdAndUserId(salonId.value, userId.value)?.toDomain()

    override fun findBySalonId(salonId: SalonId): List<SalonMembership> =
        jpaRepository.findBySalonId(salonId.value).map { it.toDomain() }

    override fun findByUserId(userId: UserId): List<SalonMembership> =
        jpaRepository.findByUserId(userId.value).map { it.toDomain() }

    private fun SalonMembershipJpaEntity.toDomain(): SalonMembership = SalonMembership.reconstitute(
        id = SalonMembershipId(id),
        salonId = SalonId(salonId),
        userId = UserId(userId),
        role = SalonRole.valueOf(role),
        createdAt = createdAt ?: Instant.EPOCH,
        updatedAt = updatedAt ?: Instant.EPOCH,
    )
}
