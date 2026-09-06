package ai.rojan.backend.infrastructure.persistence.customer

import ai.rojan.backend.domain.customer.CustomerActivityType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "customer_activities")
@EntityListeners(AuditingEntityListener::class)
class CustomerActivityJpaEntity(
    @Id
    val id: UUID,

    @Column(name = "customer_id", nullable = false)
    var customerId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var type: CustomerActivityType,

    @Column(nullable = false)
    var description: String,
) {
    @CreatedDate
    @Column(name = "occurred_at", nullable = false, updatable = false)
    var occurredAt: Instant? = null
}
