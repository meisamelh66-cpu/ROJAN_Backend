package ai.rojan.backend.infrastructure.persistence.device

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.util.UUID

/**
 * Persistence model for [ai.rojan.backend.domain.device.AuthorizedDevice].
 * [lastSeenAt]/[revokedAt] are plain, explicitly domain-set nullable
 * columns (not `@LastModifiedDate` auditing) - same shape as
 * `SalonDocumentJpaEntity.reviewedAt`/`SalonVerificationJpaEntity.reviewedAt`:
 * business-meaningful timestamps that change only when the domain layer
 * decides they should, never as a side effect of an unrelated field write.
 */
@Entity
@Table(name = "authorized_devices")
@EntityListeners(AuditingEntityListener::class)
class AuthorizedDeviceJpaEntity(
    @Id
    val id: UUID,

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "salon_id", nullable = false)
    val salonId: UUID,

    @Column(name = "device_id", nullable = false, length = 200)
    val deviceId: String,

    @Column(name = "fingerprint", nullable = true, length = 200)
    val fingerprint: String?,

    @Column(name = "installation_id", nullable = true, length = 200)
    val installationId: String?,

    @Column(name = "last_seen_at", nullable = true)
    var lastSeenAt: Instant?,

    @Column(name = "revoked_at", nullable = true)
    var revokedAt: Instant?,
) {
    @CreatedDate
    @Column(name = "registered_at", nullable = false, updatable = false)
    var registeredAt: Instant? = null
}
