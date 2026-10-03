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

    /**
     * Public Salon Onboarding - one-salon-per-account (forward-only, new creation only; see
     * [ai.rojan.backend.application.salon.CreatePublicSalonUseCase]'s own doc comment for the full
     * policy). If [ownerId] already owns at least one salon (any status - suspended or DRAFT still
     * counts), returns the earliest of them ([SalonOwnershipOutcome.AlreadyExists]) without
     * persisting [candidate] at all. Otherwise persists [candidate] and returns
     * [SalonOwnershipOutcome.Created]. Concurrency-safe: two simultaneous onboarding requests for
     * the same owner can never both create a salon (the real adapter takes a Postgres
     * transaction-scoped advisory lock keyed by [ownerId] before checking, the same pattern
     * [ai.rojan.backend.domain.booking.BookingRepository.reserve] already establishes for
     * specialist-keyed booking conflicts - see that method's own doc comment). Never touches,
     * migrates, or alters any pre-existing salon.
     */
    fun createForOwnerIfAbsent(ownerId: UserId, candidate: Salon): SalonOwnershipOutcome

    /**
     * The customer-facing salon directory: salons that are both `active` (not soft-deleted) AND
     * `onboardingStatus == ACTIVE` (finished onboarding). DRAFT salons are excluded - a customer
     * must never be shown a salon they cannot then book against. Optionally filtered by a
     * case-insensitive name substring, sorted by name.
     */
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

    /**
     * Platform Authority oversight (Admin Salon Visibility, Enterprise Scale Preparation):
     * deliberately bypasses every customer-facing discoverability gate
     * [findAllActive]/[findAllPubliclyDiscoverable] enforce - returns every salon regardless of
     * [Salon.active]/[SalonOnboardingStatus], so a platform operator can see a salon exists (and
     * its real status) the moment it's created, DRAFT included. This port itself enforces no access
     * control, same contract every other method here already follows - the caller
     * (`ListPlatformSalonsUseCase`) is solely responsible for requiring PLATFORM_ADMIN/
     * PLATFORM_REVIEWER before ever reaching this.
     *
     * Every [PlatformSalonFilter] field is optional and strictly allowlisted (never a free-form
     * query string) - see `docs/backend-requirements/platform-admin-scalability.md` §3/§4 on the
     * Website, which this signature matches field-for-field: [PlatformSalonFilter.status] is
     * derived server-side exactly like the Website's own `salonStatus()` helper (`INACTIVE` when
     * `!active`, else `PUBLISHED`/`DRAFT` from [SalonOnboardingStatus]), [PlatformSalonFilter.city]
     * is an exact, case-insensitive match (same convention [findAllPubliclyDiscoverable] already
     * uses), [PlatformSalonFilter.owner]/`name` stay case-insensitive substrings, and
     * [PlatformSalonFilter.phone] is a prefix match (never `LIKE '%…%'` - see that doc's §5 phone
     * note). Each [PlatformSalonResult.ownerName] is the owning [ai.rojan.backend.domain.user.User.fullName]
     * - resolved here (a cross-aggregate join, intentionally confined to this one infrastructure
     * adapter) so the Website's admin directory never needs a per-row owner lookup.
     */
    fun findAllForPlatform(pageRequest: PageRequest, filter: PlatformSalonFilter, sort: PlatformSalonSort): PageResult<PlatformSalonResult>
}

/** The result of [SalonRepository.createForOwnerIfAbsent] - see that method's own doc comment. */
sealed class SalonOwnershipOutcome {
    data class Created(val salon: Salon) : SalonOwnershipOutcome()
    data class AlreadyExists(val salon: Salon) : SalonOwnershipOutcome()
}

/** One [SalonRepository.findNearby] result row - the real [Salon] paired with its real, computed distance from the query point. */
data class NearbySalonResult(val salon: Salon, val distanceKm: Double)

/** One [SalonRepository.findAllForPlatform] result row - the real [Salon] paired with its owning [ai.rojan.backend.domain.user.User.fullName], or `null` if that user record is somehow missing (never fabricated). */
data class PlatformSalonResult(val salon: Salon, val ownerName: String?)

/** Directory status buckets the Website's admin salon directory filters by - derived from [Salon.active] + [SalonOnboardingStatus], never a stored column of its own. Mirrors `SALON_DIRECTORY_STATUSES` (`lib/types/platform-salon.ts`) exactly. */
enum class PlatformSalonStatus { PUBLISHED, DRAFT, INACTIVE }

/** Every field optional and strictly allowlisted - see [SalonRepository.findAllForPlatform]'s own doc comment. */
data class PlatformSalonFilter(
    val name: String? = null,
    val owner: String? = null,
    val phone: String? = null,
    val status: PlatformSalonStatus? = null,
    val verified: Boolean? = null,
    val city: String? = null,
)

/** The allowlisted sort fields the Website's admin salon directory may request - mirrors `SALON_SORT_FIELDS` (`lib/types/platform-salon.ts`) exactly; each must be backed by an index (scalability doc §5). */
enum class PlatformSalonSortField { CREATED_AT, NAME }

data class PlatformSalonSort(val field: PlatformSalonSortField, val direction: SortDirection)
