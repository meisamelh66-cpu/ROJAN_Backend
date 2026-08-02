package ai.rojan.backend.infrastructure.persistence.salon

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SalonSpringDataRepository : JpaRepository<SalonJpaEntity, UUID> {
    fun findByOwnerId(ownerId: UUID): List<SalonJpaEntity>
    fun findByActiveTrue(): List<SalonJpaEntity>
}
