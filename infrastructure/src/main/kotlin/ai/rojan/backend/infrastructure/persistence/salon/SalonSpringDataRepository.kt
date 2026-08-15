package ai.rojan.backend.infrastructure.persistence.salon

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SalonSpringDataRepository : JpaRepository<SalonJpaEntity, UUID> {
    fun findByOwnerId(ownerId: UUID): List<SalonJpaEntity>
    fun findBySlug(slug: String): SalonJpaEntity?
    fun existsBySlug(slug: String): Boolean
    fun findByActiveTrue(pageable: Pageable): Page<SalonJpaEntity>
    fun findByActiveTrueAndNameContainingIgnoreCase(name: String, pageable: Pageable): Page<SalonJpaEntity>
}
