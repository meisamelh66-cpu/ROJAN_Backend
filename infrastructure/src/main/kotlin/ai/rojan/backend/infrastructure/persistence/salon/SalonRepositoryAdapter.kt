package ai.rojan.backend.infrastructure.persistence.salon

import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.common.SortDirection
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.salon.NearbySalonResult
import ai.rojan.backend.domain.salon.PlatformSalonFilter
import ai.rojan.backend.domain.salon.PlatformSalonResult
import ai.rojan.backend.domain.salon.PlatformSalonSort
import ai.rojan.backend.domain.salon.PlatformSalonSortField
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonMembershipId
import ai.rojan.backend.domain.salon.SalonOnboardingStatus
import ai.rojan.backend.domain.salon.SalonOwnershipOutcome
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId
import org.springframework.data.domain.Sort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import org.springframework.data.domain.PageRequest as SpringPageRequest

/** Repository-pattern adapter: implements the domain [SalonRepository] port on top of Spring Data JPA. */
@Repository
class SalonRepositoryAdapter(
    private val jpaRepository: SalonSpringDataRepository,
    private val jdbcTemplate: JdbcTemplate,
) : SalonRepository {

    /**
     * Public Salon Onboarding - one-salon-per-account: takes a Postgres transaction-scoped
     * advisory lock keyed by [ownerId] before checking, so the "does this owner already have a
     * salon" check and the insert are effectively atomic under concurrency - same pattern
     * [ai.rojan.backend.infrastructure.persistence.booking.BookingRepositoryAdapter.reserve]
     * already establishes (that method's own doc comment explains why an advisory lock rather
     * than an external DB extension). Reuses [save] for the actual insert - no second,
     * possibly-drifting entity-mapping path.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun createForOwnerIfAbsent(ownerId: UserId, candidate: Salon): SalonOwnershipOutcome {
        val lockKey = ownerId.value.leastSignificantBits
        jdbcTemplate.execute("SELECT pg_advisory_xact_lock($lockKey)")
        val existing = jpaRepository.findByOwnerId(ownerId.value).map { it.toDomain() }.minByOrNull { it.createdAt }
        if (existing != null) return SalonOwnershipOutcome.AlreadyExists(existing)
        return SalonOwnershipOutcome.Created(save(candidate))
    }

    override fun save(salon: Salon): Salon {
        val entity = jpaRepository.findById(salon.id.value).orElse(null)
            ?.apply {
                name = salon.name
                description = salon.description
                phone = salon.phone
                email = salon.email
                address = salon.address
                slug = salon.slug
                onboardingStatus = salon.onboardingStatus
                logoMediaId = salon.logoMediaId?.value
                coverMediaId = salon.coverMediaId?.value
                latitude = salon.latitude
                longitude = salon.longitude
                city = salon.city
                active = salon.active
                activityStartJalaliYear = salon.activityStartJalaliYear
                hasInternalExtensions = salon.hasInternalExtensions
                sellsProducts = salon.sellsProducts
                hasCafe = salon.hasCafe
                hasStaffUniform = salon.hasStaffUniform
                isNeighborhoodSalon = salon.isNeighborhoodSalon
                isCityCenterSalon = salon.isCityCenterSalon
                primaryContactMembershipId = salon.primaryContactMembershipId?.value
                rojanVerified = salon.rojanVerified
                rojanVerifiedAt = salon.rojanVerifiedAt
            }
            ?: SalonJpaEntity(
                id = salon.id.value,
                ownerId = salon.ownerId.value,
                name = salon.name,
                description = salon.description,
                phone = salon.phone,
                email = salon.email,
                address = salon.address,
                slug = salon.slug,
                onboardingStatus = salon.onboardingStatus,
                logoMediaId = salon.logoMediaId?.value,
                coverMediaId = salon.coverMediaId?.value,
                latitude = salon.latitude,
                longitude = salon.longitude,
                city = salon.city,
                active = salon.active,
                activityStartJalaliYear = salon.activityStartJalaliYear,
                hasInternalExtensions = salon.hasInternalExtensions,
                sellsProducts = salon.sellsProducts,
                hasCafe = salon.hasCafe,
                hasStaffUniform = salon.hasStaffUniform,
                isNeighborhoodSalon = salon.isNeighborhoodSalon,
                isCityCenterSalon = salon.isCityCenterSalon,
                primaryContactMembershipId = salon.primaryContactMembershipId?.value,
                rojanVerified = salon.rojanVerified,
                rojanVerifiedAt = salon.rojanVerifiedAt,
            )
        return jpaRepository.save(entity).toDomain()
    }

    override fun findById(id: SalonId): Salon? =
        jpaRepository.findById(id.value).orElse(null)?.toDomain()

    override fun findByOwnerId(ownerId: UserId): List<Salon> =
        jpaRepository.findByOwnerId(ownerId.value).map { it.toDomain() }

    override fun findBySlug(slug: String): Salon? =
        jpaRepository.findBySlug(slug)?.toDomain()

    override fun existsBySlug(slug: String): Boolean =
        jpaRepository.existsBySlug(slug)

    override fun findAllActive(pageRequest: PageRequest, nameFilter: String?, sortDirection: SortDirection): PageResult<Salon> {
        val direction = if (sortDirection == SortDirection.ASC) Sort.Direction.ASC else Sort.Direction.DESC
        val pageable = SpringPageRequest.of(pageRequest.page, pageRequest.size, Sort.by(direction, "name"))
        // Customer-facing discovery: a salon is browsable only when it is both not soft-deleted
        // (active) AND has finished onboarding (onboardingStatus == ACTIVE) - the same rule the
        // public website surface (PublicSalonController) already enforces. A DRAFT salon can be
        // fully managed by its owner but must not appear here, otherwise a customer can walk a
        // booking flow that Salon.requireActivated() then rejects with 409 SALON_NOT_ACTIVE.
        val page = if (nameFilter.isNullOrBlank()) {
            jpaRepository.findByActiveTrueAndOnboardingStatus(SalonOnboardingStatus.ACTIVE, pageable)
        } else {
            jpaRepository.findByActiveTrueAndOnboardingStatusAndNameContainingIgnoreCase(
                SalonOnboardingStatus.ACTIVE, nameFilter, pageable,
            )
        }
        return PageResult(
            content = page.content.map { it.toDomain() },
            page = page.number,
            size = page.size,
            totalElements = page.totalElements,
        )
    }

    override fun findAllPubliclyDiscoverable(
        pageRequest: PageRequest,
        city: String?,
        nameFilter: String?,
        sortDirection: SortDirection,
    ): PageResult<Salon> {
        val direction = if (sortDirection == SortDirection.ASC) Sort.Direction.ASC else Sort.Direction.DESC
        val pageable = SpringPageRequest.of(pageRequest.page, pageRequest.size, Sort.by(direction, "name"))
        val hasCity = !city.isNullOrBlank()
        val hasName = !nameFilter.isNullOrBlank()
        val page = when {
            hasCity && hasName -> jpaRepository.findByActiveTrueAndOnboardingStatusAndCityIgnoreCaseAndNameContainingIgnoreCase(
                SalonOnboardingStatus.ACTIVE, city!!, nameFilter!!, pageable,
            )
            hasCity -> jpaRepository.findByActiveTrueAndOnboardingStatusAndCityIgnoreCase(SalonOnboardingStatus.ACTIVE, city!!, pageable)
            hasName -> jpaRepository.findByActiveTrueAndOnboardingStatusAndNameContainingIgnoreCase(
                SalonOnboardingStatus.ACTIVE, nameFilter!!, pageable,
            )
            else -> jpaRepository.findByActiveTrueAndOnboardingStatus(SalonOnboardingStatus.ACTIVE, pageable)
        }
        return PageResult(
            content = page.content.map { it.toDomain() },
            page = page.number,
            size = page.size,
            totalElements = page.totalElements,
        )
    }

    override fun findAllForPlatform(pageRequest: PageRequest, filter: PlatformSalonFilter, sort: PlatformSalonSort): PageResult<PlatformSalonResult> {
        // Blank optional text filters are treated as absent, same convention every other finder in
        // this adapter already follows (isNullOrBlank(), never an empty-string bind value that
        // would make `ILIKE '%' || :owner || '%'` match every row).
        val name = filter.name?.trim()?.ifBlank { null }
        val owner = filter.owner?.trim()?.ifBlank { null }
        val phone = filter.phone?.trim()?.ifBlank { null }
        val city = filter.city?.trim()?.ifBlank { null }
        val status = filter.status?.name
        val pageable = SpringPageRequest.of(pageRequest.page, pageRequest.size)

        // Platform Authority oversight: deliberately no active/onboardingStatus predicate baked
        // into the query shape itself - every salon, any status, unlike findAllActive/
        // findAllPubliclyDiscoverable above. `status` is instead one optional filter among the
        // others, derived server-side inside PLATFORM_SALON_SELECT/_COUNT.
        val idPage = when (sort.field to sort.direction) {
            PlatformSalonSortField.NAME to SortDirection.ASC ->
                jpaRepository.findForPlatformOrderByNameAsc(name, owner, phone, status, filter.verified, city, pageable)
            PlatformSalonSortField.NAME to SortDirection.DESC ->
                jpaRepository.findForPlatformOrderByNameDesc(name, owner, phone, status, filter.verified, city, pageable)
            PlatformSalonSortField.CREATED_AT to SortDirection.ASC ->
                jpaRepository.findForPlatformOrderByCreatedAtAsc(name, owner, phone, status, filter.verified, city, pageable)
            else ->
                jpaRepository.findForPlatformOrderByCreatedAtDesc(name, owner, phone, status, filter.verified, city, pageable)
        }

        // Hydrates the real, full entities for exactly the paged ids the native query returned,
        // reusing the same `toDomain()` mapping every other finder already uses - same precedent
        // findNearby below already establishes for native-query result hydration. `findAllById`
        // does not preserve the native query's own order, so the real order (and each row's real
        // ownerName) comes from `idPage.content` itself, not from re-deriving it.
        val ownerNameById = idPage.content.associate { it.getId() to it.getOwnerName() }
        val entityById = jpaRepository.findAllById(idPage.content.map { it.getId() }).associateBy { it.id }
        val content = idPage.content.mapNotNull { row ->
            entityById[row.getId()]?.let { PlatformSalonResult(it.toDomain(), ownerNameById[row.getId()]) }
        }

        return PageResult(content = content, page = idPage.number, size = idPage.size, totalElements = idPage.totalElements)
    }

    override fun findNearby(lat: Double, lng: Double, radiusKm: Double, pageRequest: PageRequest): PageResult<NearbySalonResult> {
        val pageable = SpringPageRequest.of(pageRequest.page, pageRequest.size)
        val rows = jpaRepository.findNearbyIdsWithDistance(lat, lng, radiusKm, SalonOnboardingStatus.ACTIVE.name, pageable)

        // Hydrates the real, full entities for exactly the paged-and-distance-sorted ids the native
        // query returned, reusing the same `toDomain()` mapping every other finder already uses -
        // never a hand-rolled second mapping for this one query. `findAllById` does not preserve the
        // native query's own distance-ascending order, so the real order (and each row's own real
        // distance) comes from `rows.content` itself, not from re-deriving it.
        val entityById = jpaRepository.findAllById(rows.content.map { it.getId() }).associateBy { it.id }
        val content = rows.content.mapNotNull { row -> entityById[row.getId()]?.let { NearbySalonResult(it.toDomain(), row.getDistanceKm()) } }

        return PageResult(content = content, page = rows.number, size = rows.size, totalElements = rows.totalElements)
    }

    private fun SalonJpaEntity.toDomain(): Salon = Salon.reconstitute(
        id = SalonId(id),
        ownerId = UserId(ownerId),
        name = name,
        description = description,
        phone = phone,
        email = email,
        address = address,
        slug = slug,
        onboardingStatus = onboardingStatus,
        logoMediaId = logoMediaId?.let { MediaAssetId(it) },
        coverMediaId = coverMediaId?.let { MediaAssetId(it) },
        latitude = latitude,
        longitude = longitude,
        city = city,
        active = active,
        activityStartJalaliYear = activityStartJalaliYear,
        hasInternalExtensions = hasInternalExtensions,
        sellsProducts = sellsProducts,
        hasCafe = hasCafe,
        hasStaffUniform = hasStaffUniform,
        isNeighborhoodSalon = isNeighborhoodSalon,
        isCityCenterSalon = isCityCenterSalon,
        primaryContactMembershipId = primaryContactMembershipId?.let { SalonMembershipId(it) },
        rojanVerified = rojanVerified,
        rojanVerifiedAt = rojanVerifiedAt,
        createdAt = createdAt ?: Instant.EPOCH,
        updatedAt = updatedAt ?: Instant.EPOCH,
    )
}
