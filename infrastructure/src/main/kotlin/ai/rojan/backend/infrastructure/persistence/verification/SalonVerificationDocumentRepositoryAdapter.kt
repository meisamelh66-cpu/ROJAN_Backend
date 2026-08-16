package ai.rojan.backend.infrastructure.persistence.verification

import ai.rojan.backend.domain.document.SalonDocumentId
import ai.rojan.backend.domain.verification.SalonVerificationDocumentRepository
import ai.rojan.backend.domain.verification.SalonVerificationId
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/** Repository-pattern adapter: implements the domain [SalonVerificationDocumentRepository] port on top of Spring Data JPA. */
@Repository
class SalonVerificationDocumentRepositoryAdapter(
    private val jpaRepository: SalonVerificationDocumentSpringDataRepository,
) : SalonVerificationDocumentRepository {

    override fun saveAll(verificationId: SalonVerificationId, documentIds: List<SalonDocumentId>) {
        val entities = documentIds.map { documentId ->
            SalonVerificationDocumentJpaEntity(
                id = UUID.randomUUID(),
                verificationId = verificationId.value,
                documentId = documentId.value,
                attachedAt = Instant.now(),
            )
        }
        jpaRepository.saveAll(entities)
    }

    override fun findDocumentIdsByVerificationId(verificationId: SalonVerificationId): List<SalonDocumentId> =
        jpaRepository.findByVerificationId(verificationId.value).map { SalonDocumentId(it.documentId) }
}
