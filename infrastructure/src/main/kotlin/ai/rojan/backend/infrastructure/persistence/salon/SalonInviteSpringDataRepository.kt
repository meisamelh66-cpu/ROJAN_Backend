package ai.rojan.backend.infrastructure.persistence.salon

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SalonInviteSpringDataRepository : JpaRepository<SalonInviteJpaEntity, UUID> {
    fun findByToken(token: String): SalonInviteJpaEntity?
    fun findBySalonId(salonId: UUID): List<SalonInviteJpaEntity>
}
