package ai.rojan.backend.infrastructure.persistence.banner

import ai.rojan.backend.domain.banner.BannerTarget
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface BannerSpringDataRepository : JpaRepository<BannerJpaEntity, UUID> {
    fun findByTarget(target: BannerTarget): List<BannerJpaEntity>
    fun findByTargetAndIsActiveTrue(target: BannerTarget): List<BannerJpaEntity>
}
