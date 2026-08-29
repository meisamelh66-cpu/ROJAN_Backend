package ai.rojan.backend.infrastructure.persistence.salon

import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.common.SortDirection
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonOnboardingStatus
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Repository
import java.time.Instant
import org.springframework.data.domain.PageRequest as SpringPageRequest

/** Repository-pattern adapter: implements the domain [SalonRepository] port on top of Spring Data JPA. */
@Repository
class SalonRepositoryAdapter(
    private val jpaRepository: SalonSpringDataRepository,
) : SalonRepository {

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
        val page = if (nameFilter.isNullOrBlank()) {
            jpaRepository.findByActiveTrue(pageable)
        } else {
            jpaRepository.findByActiveTrueAndNameContainingIgnoreCase(nameFilter, pageable)
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
        createdAt = createdAt ?: Instant.EPOCH,
        updatedAt = updatedAt ?: Instant.EPOCH,
    )
}
