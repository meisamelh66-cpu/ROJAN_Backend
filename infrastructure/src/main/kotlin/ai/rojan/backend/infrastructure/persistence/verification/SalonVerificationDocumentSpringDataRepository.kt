package ai.rojan.backend.infrastructure.persistence.verification

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SalonVerificationDocumentSpringDataRepository : JpaRepository<SalonVerificationDocumentJpaEntity, UUID> {
    fun findByVerificationId(verificationId: UUID): List<SalonVerificationDocumentJpaEntity>
}
