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


    // Existing active salon discovery
    fun findByActiveTrue(
        pageable: Pageable,
    ): Page<SalonJpaEntity>

    fun findByActiveTrueAndNameContainingIgnoreCase(
        name: String,
        pageable: Pageable,
    ): Page<SalonJpaEntity>


    // Public Salon Marketplace + Booking safety:
    // Only active salons with the requested onboarding status
    fun findByActiveTrueAndOnboardingStatus(
        onboardingStatus: SalonOnboardingStatus,
        pageable: Pageable,
    ): Page<SalonJpaEntity>


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


    // LBS Architecture:
    // Calculates real distance using Postgres Haversine formula.
    // Returns only ids + distance; adapter hydrates full entities.
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


/**
 * Projection for LBS query result.
 */
interface NearbySalonIdDistanceRow {

    fun getId(): UUID

    fun getDistanceKm(): Double
}