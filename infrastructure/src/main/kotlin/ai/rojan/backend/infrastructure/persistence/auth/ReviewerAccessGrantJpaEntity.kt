package ai.rojan.backend.infrastructure.persistence.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant

@Entity
@Table(name = "reviewer_access_grants")
@EntityListeners(AuditingEntityListener::class)
class ReviewerAccessGrantJpaEntity(
    @Id
    @Column(name = "phone_number", length = 32)
    val phoneNumber: String,

    @Column(name = "code_hash", nullable = false, length = 64)
    var codeHash: String,

    @Column(name = "activated_at", nullable = false)
    var activatedAt: Instant,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    @Column(name = "active", nullable = false)
    var active: Boolean,
) {
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
}
