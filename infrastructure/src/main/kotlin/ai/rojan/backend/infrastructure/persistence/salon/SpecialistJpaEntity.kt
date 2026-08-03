package ai.rojan.backend.infrastructure.persistence.salon

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "specialists")
@EntityListeners(AuditingEntityListener::class)
class SpecialistJpaEntity(
    @Id
    val id: UUID,

    @Column(name = "salon_id", nullable = false)
    var salonId: UUID,

    @Column(name = "user_id", nullable = true)
    var userId: UUID?,

    @Column(name = "display_name", nullable = false)
    var displayName: String,

    @Column(nullable = true)
    var bio: String?,

    @Column(name = "photo_url", nullable = true)
    var photoUrl: String?,

    @Column(nullable = false)
    var active: Boolean,
) {
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
}
