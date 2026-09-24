package ai.rojan.backend.infrastructure.persistence.verification

import ai.rojan.backend.domain.verification.SalonVerificationStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SalonVerificationSpringDataRepository : JpaRepository<SalonVerificationJpaEntity, UUID> {
    fun findByIdAndSalonId(id: UUID, salonId: UUID): SalonVerificationJpaEntity?
    fun findBySalonIdOrderByCreatedAtDesc(salonId: UUID): List<SalonVerificationJpaEntity>
    fun findFirstBySalonIdAndStatusIn(salonId: UUID, statuses: List<SalonVerificationStatus>): SalonVerificationJpaEntity?

    /** Platform Authority review queue (Phase 4). */
    fun findByStatusIn(statuses: List<SalonVerificationStatus>, pageable: Pageable): Page<SalonVerificationJpaEntity>
}
