package ai.rojan.backend.infrastructure.persistence.media

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface MediaAssetSpringDataRepository : JpaRepository<MediaAssetJpaEntity, UUID> {
    fun findByIdAndSalonId(id: UUID, salonId: UUID): MediaAssetJpaEntity?
    fun findBySalonId(salonId: UUID): List<MediaAssetJpaEntity>
}
