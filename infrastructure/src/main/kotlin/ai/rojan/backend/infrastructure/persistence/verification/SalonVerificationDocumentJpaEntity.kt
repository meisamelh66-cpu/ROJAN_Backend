package ai.rojan.backend.infrastructure.persistence.verification

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Persistence model for [ai.rojan.backend.domain.verification.SalonVerificationDocument] - the immutable join row linking a case to one of the documents it covers. No auditing listener: written once at submit time, never updated. */
@Entity
@Table(name = "salon_verification_documents")
class SalonVerificationDocumentJpaEntity(
    @Id
    val id: UUID,

    @Column(name = "verification_id", nullable = false)
    val verificationId: UUID,

    @Column(name = "document_id", nullable = false)
    val documentId: UUID,

    @Column(name = "attached_at", nullable = false)
    val attachedAt: Instant,
)
