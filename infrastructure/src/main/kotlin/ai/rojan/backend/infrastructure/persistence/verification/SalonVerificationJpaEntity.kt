package ai.rojan.backend.infrastructure.persistence.verification

import ai.rojan.backend.domain.verification.SalonVerificationStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.util.UUID

/**
 * Persistence model for [ai.rojan.backend.domain.verification.SalonVerification].
 * Deliberately separate from the domain entity, mirroring
 * [ai.rojan.backend.infrastructure.persistence.document.SalonDocumentJpaEntity];
 * [SalonVerificationRepositoryAdapter] maps between the two. [version]
 * backs JPA optimistic locking - two Platform Authority reviewers claiming
 * the same case concurrently is a real race, not a hypothetical one.
 */
@Entity
@Table(name = "salon_verifications")
@EntityListeners(AuditingEntityListener::class)
class SalonVerificationJpaEntity(
    @Id
    val id: UUID,

    @Column(name = "salon_id", nullable = false)
    val salonId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: SalonVerificationStatus,

    @Column(name = "submitted_by", nullable = false)
    val submittedBy: UUID,

    @Column(name = "submitted_at", nullable = false)
    val submittedAt: Instant,

    @Column(name = "reviewed_by", nullable = true)
    var reviewedBy: UUID?,

    @Column(name = "reviewed_at", nullable = true)
    var reviewedAt: Instant?,

    @Column(name = "rejection_reason", nullable = true, length = 1000)
    var rejectionReason: String?,
) {
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
}
