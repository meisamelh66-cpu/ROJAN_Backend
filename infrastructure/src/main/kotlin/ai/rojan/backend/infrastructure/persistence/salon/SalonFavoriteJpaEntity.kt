package ai.rojan.backend.infrastructure.persistence.salon

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "salon_favorites")
@EntityListeners(AuditingEntityListener::class)
class SalonFavoriteJpaEntity(
    @Id
    val id: UUID,

    @Column(name = "customer_id", nullable = false)
    val customerId: UUID,

    @Column(name = "salon_id", nullable = false)
    val salonId: UUID,
) {
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
}
