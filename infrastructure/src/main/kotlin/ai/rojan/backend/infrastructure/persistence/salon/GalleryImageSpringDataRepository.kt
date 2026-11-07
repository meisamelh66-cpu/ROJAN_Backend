package ai.rojan.backend.infrastructure.persistence.salon

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface GalleryImageSpringDataRepository : JpaRepository<GalleryImageJpaEntity, UUID> {
    fun findBySalonIdOrderByDisplayOrderAsc(salonId: UUID): List<GalleryImageJpaEntity>
}
