package ai.rojan.backend.infrastructure.persistence.banner

import ai.rojan.backend.domain.banner.Banner
import ai.rojan.backend.domain.banner.BannerId
import ai.rojan.backend.domain.banner.BannerRepository
import ai.rojan.backend.domain.banner.BannerTarget
import ai.rojan.backend.domain.user.UserId
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class BannerRepositoryAdapter(
    private val jpaRepository: BannerSpringDataRepository,
) : BannerRepository {

    override fun findById(id: BannerId): Banner? =
        jpaRepository.findById(id.value).orElse(null)?.toDomain()

    override fun findByTarget(target: BannerTarget): List<Banner> =
        jpaRepository.findByTarget(target).map { it.toDomain() }.sortedBy { it.displayOrder }

    override fun findActiveByTarget(target: BannerTarget): List<Banner> =
        jpaRepository.findByTargetAndIsActiveTrue(target).map { it.toDomain() }.sortedBy { it.displayOrder }

    override fun save(banner: Banner): Banner {
        val entity = jpaRepository.findById(banner.id.value).orElse(null)
            ?.apply {
                title = banner.title
                subtitle = banner.subtitle
                href = banner.href
                storageKey = banner.storageKey
                isActive = banner.isActive
                displayOrder = banner.displayOrder
            }
            ?: BannerJpaEntity(
                id = banner.id.value,
                target = banner.target,
                title = banner.title,
                subtitle = banner.subtitle,
                href = banner.href,
                storageKey = banner.storageKey,
                isActive = banner.isActive,
                displayOrder = banner.displayOrder,
                createdBy = banner.createdBy.value,
            )
        return jpaRepository.save(entity).toDomain()
    }

    override fun delete(id: BannerId) {
        jpaRepository.deleteById(id.value)
    }

    private fun BannerJpaEntity.toDomain(): Banner = Banner.reconstitute(
        id = BannerId(id),
        target = target,
        title = title,
        subtitle = subtitle,
        href = href,
        storageKey = storageKey,
        isActive = isActive,
        displayOrder = displayOrder,
        createdBy = UserId(createdBy),
        createdAt = createdAt ?: Instant.EPOCH,
        updatedAt = updatedAt ?: Instant.EPOCH,
    )
}
