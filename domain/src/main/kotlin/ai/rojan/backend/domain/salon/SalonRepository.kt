package ai.rojan.backend.domain.salon

import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.common.SortDirection
import ai.rojan.backend.domain.user.UserId

/**
 * Output port for salon persistence. Implemented by an adapter in the
 * infrastructure module (repository pattern) — the domain layer never
 * depends on a persistence framework.
 */
interface SalonRepository {
    fun save(salon: Salon): Salon
    fun findById(id: SalonId): Salon?
    fun findByOwnerId(ownerId: UserId): List<Salon>
    fun findBySlug(slug: String): Salon?
    fun existsBySlug(slug: String): Boolean

    /** Browses active salons, optionally filtered by a case-insensitive name substring, sorted by name. */
    fun findAllActive(pageRequest: PageRequest, nameFilter: String?, sortDirection: SortDirection): PageResult<Salon>

    /**
     * Public Salon Marketplace (Phase 1): the public-discoverability query - deliberately not
     * [findAllActive] (which only checks [Salon.active], never [SalonOnboardingStatus]). A salon
     * must be both `active` (not soft-deleted) AND `onboardingStatus == ACTIVE` (finished
     * onboarding - see [SalonOnboardingStatus]'s own doc comment: "gates public discoverability
     * only") to ever appear here; a still-[SalonOnboardingStatus.DRAFT] salon must never leak into
     * this listing. [city] is an exact, case-insensitive match (a marketplace city-select filter,
     * not a free-text search); [nameFilter] stays a case-insensitive substring, same as
     * [findAllActive]'s own. Sorted by name.
     */
    fun findAllPubliclyDiscoverable(
        pageRequest: PageRequest,
        city: String?,
        nameFilter: String?,
        sortDirection: SortDirection,
    ): PageResult<Salon>

    /**
     * LBS Architecture (Phase 5): the public "nearby salons" query - same public-discoverability
     * gate as [findAllPubliclyDiscoverable] (`active` AND `onboardingStatus == ACTIVE`), further
     * restricted to salons that have actually completed location profile setup (real, non-null
     * [Salon.latitude]/[Salon.longitude] - a salon without one is honestly absent from "nearby"
     * results, never assigned a fabricated distance). [NearbySalonResult.distanceKm] is a real,
     * computed great-circle (Haversine) distance in kilometers, sorted ascending - the entire point
     * of a "nearby" query. [radiusKm] is a hard cutoff, not a soft ranking signal.
     */
    fun findNearby(lat: Double, lng: Double, radiusKm: Double, pageRequest: PageRequest): PageResult<NearbySalonResult>
}

/** One [findNearby] result row - the real [Salon] paired with its real, computed distance from the query point. */
data class NearbySalonResult(val salon: Salon, val distanceKm: Double)
