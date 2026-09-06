package ai.rojan.backend.infrastructure.persistence.customer

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
@Table(name = "customer_tags")
@EntityListeners(AuditingEntityListener::class)
class CustomerTagJpaEntity(
    @Id
    val id: UUID,

    @Column(name = "customer_id", nullable = false)
    var customerId: UUID,

    @Column(nullable = false)
    var label: String,
) {
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
}
