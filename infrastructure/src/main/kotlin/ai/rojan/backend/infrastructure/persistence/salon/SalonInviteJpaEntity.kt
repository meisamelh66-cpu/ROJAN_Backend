package ai.rojan.backend.infrastructure.persistence.salon

import ai.rojan.backend.domain.salon.SalonInviteStatus
import ai.rojan.backend.domain.salon.SalonRole
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
import java.util.UUID

/**
 * Persistence model for [ai.rojan.backend.domain.salon.SalonInvite].
 * Deliberately separate from the domain entity, mirroring
 * [SalonJpaEntity]/[SalonRepositoryAdapter]'s existing split.
 */
@Entity
@Table(name = "salon_invites")
@EntityListeners(AuditingEntityListener::class)
class SalonInviteJpaEntity(
    @Id
    val id: UUID,

    @Column(name = "salon_id", nullable = false)
    var salonId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var role: SalonRole,

    @Column(nullable = false, unique = true, length = 64)
    var token: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: SalonInviteStatus,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    @Column(name = "created_by", nullable = false)
    var createdBy: UUID,

    @Column(name = "accepted_by", nullable = true)
    var acceptedBy: UUID?,
) {
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
}
