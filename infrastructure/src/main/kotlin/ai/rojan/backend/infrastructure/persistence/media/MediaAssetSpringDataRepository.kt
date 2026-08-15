package ai.rojan.backend.infrastructure.persistence.media

import ai.rojan.backend.domain.media.MediaType
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface MediaAssetSpringDataRepository : JpaRepository<MediaAssetJpaEntity, UUID> {
    fun findBySalonId(salonId: UUID): List<MediaAssetJpaEntity>
    fun findBySalonIdAndMediaType(salonId: UUID, mediaType: MediaType): List<MediaAssetJpaEntity>
}
