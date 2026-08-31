package ai.rojan.backend.infrastructure.persistence.salon

import ai.rojan.backend.domain.salon.SalonOnboardingStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
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

    // LBS Architecture (Phase 5): real great-circle (Haversine) distance in kilometers, computed in
    // SQL - the first native query in this codebase, needed because JPQL has no portable
    // trig/radians functions (Postgres's `sin`/`cos`/`acos`/`radians` do). `LEAST(1.0, GREATEST(-1.0,
    // ...))` guards `acos`'s domain against floating-point rounding pushing its argument fractionally
    // past 1.0 for a near-zero (or exactly zero, e.g. the query point coinciding with a salon's own
    // coordinates) distance, which would otherwise produce `NaN`. Deliberately returns only
    // `id`/`distance_km` (not the full entity) - `SalonRepositoryAdapter.findNearby` hydrates the
    // full, real `SalonJpaEntity` rows afterward via the existing `findAllById`, reusing the same
    // `toDomain()` mapping every other finder already uses rather than duplicating it in a projection.
    @Query(
        value = """
            SELECT id AS id, (
                6371 * acos(
                    LEAST(1.0, GREATEST(-1.0,
                        cos(radians(:lat)) * cos(radians(latitude)) * cos(radians(longitude) - radians(:lng))
                        + sin(radians(:lat)) * sin(radians(latitude))
                    ))
                )
            ) AS distance_km
            FROM salons
            WHERE active = true
              AND onboarding_status = :onboardingStatus
              AND latitude IS NOT NULL
              AND longitude IS NOT NULL
              AND (
                6371 * acos(
                    LEAST(1.0, GREATEST(-1.0,
                        cos(radians(:lat)) * cos(radians(latitude)) * cos(radians(longitude) - radians(:lng))
                        + sin(radians(:lat)) * sin(radians(latitude))
                    ))
                )
              ) <= :radiusKm
            ORDER BY distance_km ASC
        """,
        countQuery = """
            SELECT count(*)
            FROM salons
            WHERE active = true
              AND onboarding_status = :onboardingStatus
              AND latitude IS NOT NULL
              AND longitude IS NOT NULL
              AND (
                6371 * acos(
                    LEAST(1.0, GREATEST(-1.0,
                        cos(radians(:lat)) * cos(radians(latitude)) * cos(radians(longitude) - radians(:lng))
                        + sin(radians(:lat)) * sin(radians(latitude))
                    ))
                )
              ) <= :radiusKm
        """,
        nativeQuery = true,
    )
    fun findNearbyIdsWithDistance(
        @Param("lat") lat: Double,
        @Param("lng") lng: Double,
        @Param("radiusKm") radiusKm: Double,
        @Param("onboardingStatus") onboardingStatus: String,
        pageable: Pageable,
    ): Page<NearbySalonIdDistanceRow>
}

/** Closed interface projection for [SalonSpringDataRepository.findNearbyIdsWithDistance]'s two-column native query result. */
interface NearbySalonIdDistanceRow {
    fun getId(): UUID
    fun getDistanceKm(): Double
}
