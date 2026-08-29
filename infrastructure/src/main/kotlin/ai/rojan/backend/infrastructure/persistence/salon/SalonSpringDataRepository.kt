package ai.rojan.backend.infrastructure.persistence.salon

import ai.rojan.backend.domain.salon.SalonOnboardingStatus
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

    // Public Salon Marketplace (Phase 1): the four real filter combinations
    // `findAllPubliclyDiscoverable` needs (no city/name, city only, name only,
    // both) - always scoped by both `active = true` and the real
    // `onboardingStatus` passed in (the adapter always passes `ACTIVE`; kept
    // as a parameter here so this JPA-layer method stays generic rather than
    // hardcoding a domain-layer meaning).
    fun findByActiveTrueAndOnboardingStatus(onboardingStatus: SalonOnboardingStatus, pageable: Pageable): Page<SalonJpaEntity>

    fun findByActiveTrueAndOnboardingStatusAndCityIgnoreCase(
        onboardingStatus: SalonOnboardingStatus,
        city: String,
        pageable: Pageable,
    ): Page<SalonJpaEntity>

    fun findByActiveTrueAndOnboardingStatusAndNameContainingIgnoreCase(
        onboardingStatus: SalonOnboardingStatus,
        name: String,
        pageable: Pageable,
    ): Page<SalonJpaEntity>

    fun findByActiveTrueAndOnboardingStatusAndCityIgnoreCaseAndNameContainingIgnoreCase(
        onboardingStatus: SalonOnboardingStatus,
        city: String,
        name: String,
        pageable: Pageable,
    ): Page<SalonJpaEntity>
}
