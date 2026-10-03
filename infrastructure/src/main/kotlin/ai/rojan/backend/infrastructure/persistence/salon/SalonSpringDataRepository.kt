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


    // Platform Authority oversight (Admin Salon Visibility): every salon regardless of status -
    // see SalonRepository.findAllForPlatform. `findAll(pageable)` (no filter) is already inherited
    // from JpaRepository, so only the name-filtered variant needs a new derived query here.
    fun findByNameContainingIgnoreCase(
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


    // Platform Authority oversight (Admin Salon Visibility, Enterprise Scale Preparation): the
    // richer owner/phone/status/verified/city-filtered, sortable directory -
    // docs/backend-requirements/platform-admin-scalability.md §3/§4. A real SQL JOIN against
    // `users` (never modeled as a JPA relationship on SalonJpaEntity - Salon/User stay separate
    // aggregates everywhere else in this codebase) so the adapter can resolve each row's real
    // owner name without a per-row lookup; confined to this one native query, the only place in
    // the persistence layer that crosses the two aggregates' tables directly. Every filter is an
    // optional, explicitly-named bind parameter (`:param IS NULL OR ...`) - never a free-form
    // filter string or dynamically-built SQL - and `status` is derived here exactly as the
    // Website's own `salonStatus()` helper derives it client-side, so the two can never disagree.
    // Sort has exactly two allowlisted fields (`createdAt`, `name|)  x  two directions - four
    // explicit query methods, the same "enumerate the real combinations" convention
    // findByActiveTrueAndOnboardingStatusAndCityIgnoreCaseAndNameContainingIgnoreCase above already
    // established, rather than building ORDER BY dynamically from user input.
    @Query(
        value = PLATFORM_SALON_SELECT + "ORDER BY s.created_at DESC",
        countQuery = PLATFORM_SALON_COUNT,
        nativeQuery = true,
    )
    fun findForPlatformOrderByCreatedAtDesc(
        @Param("name") name: String?,
        @Param("owner") owner: String?,
        @Param("phone") phone: String?,
        @Param("status") status: String?,
        @Param("verified") verified: Boolean?,
        @Param("city") city: String?,
        pageable: Pageable,
    ): Page<PlatformSalonIdRow>

    @Query(
        value = PLATFORM_SALON_SELECT + "ORDER BY s.created_at ASC",
        countQuery = PLATFORM_SALON_COUNT,
        nativeQuery = true,
    )
    fun findForPlatformOrderByCreatedAtAsc(
        @Param("name") name: String?,
        @Param("owner") owner: String?,
        @Param("phone") phone: String?,
        @Param("status") status: String?,
        @Param("verified") verified: Boolean?,
        @Param("city") city: String?,
        pageable: Pageable,
    ): Page<PlatformSalonIdRow>

    @Query(
        value = PLATFORM_SALON_SELECT + "ORDER BY s.name ASC",
        countQuery = PLATFORM_SALON_COUNT,
        nativeQuery = true,
    )
    fun findForPlatformOrderByNameAsc(
        @Param("name") name: String?,
        @Param("owner") owner: String?,
        @Param("phone") phone: String?,
        @Param("status") status: String?,
        @Param("verified") verified: Boolean?,
        @Param("city") city: String?,
        pageable: Pageable,
    ): Page<PlatformSalonIdRow>

    @Query(
        value = PLATFORM_SALON_SELECT + "ORDER BY s.name DESC",
        countQuery = PLATFORM_SALON_COUNT,
        nativeQuery = true,
    )
    fun findForPlatformOrderByNameDesc(
        @Param("name") name: String?,
        @Param("owner") owner: String?,
        @Param("phone") phone: String?,
        @Param("status") status: String?,
        @Param("verified") verified: Boolean?,
        @Param("city") city: String?,
        pageable: Pageable,
    ): Page<PlatformSalonIdRow>


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

/**
 * Shared `WHERE` clause for the four `findForPlatformOrderBy*` methods above - a Kotlin `const val`
 * so it is still a compile-time constant each `@Query` annotation can reference, keeping the real
 * filter logic written exactly once despite the four explicit sort-variant methods.
 */
// Every optional bind parameter is explicitly cast via standard SQL CAST(:param AS type) at each
// `IS NULL` check - without it, PostgreSQL cannot determine a bind parameter's type from
// `:param IS NULL` alone (no surrounding operator to infer from) and fails the whole query with
// "could not determine data type of parameter $N", confirmed by a real Testcontainers Postgres run
// against this exact query before any cast was added. Postgres's own `:param::type` shorthand was
// tried first and rejected: Hibernate's native-query parameter scanner absorbs the `::type` suffix
// into the parameter name itself (`UnknownParameterException: No parameter named ':name'... named
// parameters [name::text, ...]`), also confirmed by a real run - CAST(... AS ...) has no such
// ambiguity. The later, typed usage of the same named parameter (`ILIKE`, `=`) does not help - each
// occurrence of a named parameter becomes its own independently-typed JDBC bind position.
private const val PLATFORM_SALON_SELECT = """
    SELECT s.id AS id, u.full_name AS ownerName
    FROM salons s
    JOIN users u ON u.id = s.owner_id
    WHERE (CAST(:name AS text) IS NULL OR s.name ILIKE '%' || CAST(:name AS text) || '%')
      AND (CAST(:owner AS text) IS NULL OR u.full_name ILIKE '%' || CAST(:owner AS text) || '%')
      AND (CAST(:phone AS text) IS NULL OR s.phone LIKE CAST(:phone AS text) || '%')
      AND (CAST(:verified AS boolean) IS NULL OR s.rojan_verified = CAST(:verified AS boolean))
      AND (CAST(:city AS text) IS NULL OR s.city ILIKE CAST(:city AS text))
      AND (
        CAST(:status AS text) IS NULL
        OR (CAST(:status AS text) = 'INACTIVE' AND s.active = false)
        OR (CAST(:status AS text) = 'PUBLISHED' AND s.active = true AND s.onboarding_status = 'ACTIVE')
        OR (CAST(:status AS text) = 'DRAFT' AND s.active = true AND s.onboarding_status = 'DRAFT')
      )
    """

private const val PLATFORM_SALON_COUNT = """
    SELECT count(*)
    FROM salons s
    JOIN users u ON u.id = s.owner_id
    WHERE (CAST(:name AS text) IS NULL OR s.name ILIKE '%' || CAST(:name AS text) || '%')
      AND (CAST(:owner AS text) IS NULL OR u.full_name ILIKE '%' || CAST(:owner AS text) || '%')
      AND (CAST(:phone AS text) IS NULL OR s.phone LIKE CAST(:phone AS text) || '%')
      AND (CAST(:verified AS boolean) IS NULL OR s.rojan_verified = CAST(:verified AS boolean))
      AND (CAST(:city AS text) IS NULL OR s.city ILIKE CAST(:city AS text))
      AND (
        CAST(:status AS text) IS NULL
        OR (CAST(:status AS text) = 'INACTIVE' AND s.active = false)
        OR (CAST(:status AS text) = 'PUBLISHED' AND s.active = true AND s.onboarding_status = 'ACTIVE')
        OR (CAST(:status AS text) = 'DRAFT' AND s.active = true AND s.onboarding_status = 'DRAFT')
      )
    """

/** Projection for the platform salon directory query - only the id (to hydrate the real [SalonJpaEntity] from) plus the one cross-aggregate field ([ownerName]) the native query alone can resolve. */
interface PlatformSalonIdRow {
    fun getId(): UUID
    fun getOwnerName(): String?
}