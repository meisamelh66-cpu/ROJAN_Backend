package ai.rojan.backend.infrastructure.persistence.document

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SalonDocumentSpringDataRepository : JpaRepository<SalonDocumentJpaEntity, UUID> {
    fun findByIdAndSalonId(id: UUID, salonId: UUID): SalonDocumentJpaEntity?
    fun findBySalonId(salonId: UUID): List<SalonDocumentJpaEntity>
    fun findByMediaAssetId(mediaAssetId: UUID): SalonDocumentJpaEntity?
}
