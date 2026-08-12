package ai.rojan.backend.infrastructure.persistence.salon

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SalonMembershipSpringDataRepository : JpaRepository<SalonMembershipJpaEntity, UUID> {
    fun findBySalonIdAndUserId(salonId: UUID, userId: UUID): SalonMembershipJpaEntity?
    fun findBySalonId(salonId: UUID): List<SalonMembershipJpaEntity>
    fun findByUserId(userId: UUID): List<SalonMembershipJpaEntity>
    fun deleteBySalonIdAndUserId(salonId: UUID, userId: UUID)
}
