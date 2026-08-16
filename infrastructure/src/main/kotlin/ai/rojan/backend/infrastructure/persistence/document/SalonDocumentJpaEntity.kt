package ai.rojan.backend.infrastructure.persistence.document

import ai.rojan.backend.domain.document.DocumentType
import ai.rojan.backend.domain.document.DocumentVerificationStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Persistence model for [ai.rojan.backend.domain.document.SalonDocument].
 * Deliberately separate from the domain entity so JPA/Hibernate concerns
 * never leak into the domain layer; [SalonDocumentRepositoryAdapter] maps
 * between the two.
 */
@Entity
@Table(name = "salon_documents")
@EntityListeners(AuditingEntityListener::class)
class SalonDocumentJpaEntity(
    @Id
    val id: UUID,

    @Column(name = "salon_id", nullable = false)
    val salonId: UUID,

    @Column(name = "media_asset_id", nullable = false)
    val mediaAssetId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 16)
    val documentType: DocumentType,

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 16)
    var verificationStatus: DocumentVerificationStatus,

    @Column(name = "expiry_date", nullable = true)
    val expiryDate: LocalDate?,

    @Column(name = "uploaded_by", nullable = false)
    val uploadedBy: UUID,
) {
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
}
