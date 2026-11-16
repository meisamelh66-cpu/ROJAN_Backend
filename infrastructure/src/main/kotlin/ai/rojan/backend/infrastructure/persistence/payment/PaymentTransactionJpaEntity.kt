package ai.rojan.backend.infrastructure.persistence.payment

import ai.rojan.backend.domain.payment.PaymentStatus
import ai.rojan.backend.domain.payment.PaymentTransactionMethod
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

@Entity
@Table(name = "payment_transactions")
@EntityListeners(AuditingEntityListener::class)
class PaymentTransactionJpaEntity(
    @Id
    val id: UUID,

    @Column(name = "salon_id", nullable = false)
    var salonId: UUID,

    @Column(name = "booking_id", nullable = true)
    var bookingId: UUID?,

    @Column(name = "customer_id", nullable = false)
    var customerId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var method: PaymentTransactionMethod,

    @Column(nullable = false)
    var amount: Long,

    @Column(nullable = false, length = 3)
    var currency: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: PaymentStatus,

    @Column(name = "gateway_provider", nullable = true, length = 32)
    var gatewayProvider: String?,

    @Column(name = "gateway_authority", nullable = true, length = 128)
    var gatewayAuthority: String?,

    @Column(name = "gateway_reference_id", nullable = true, length = 128)
    var gatewayReferenceId: String?,

    @Column(name = "idempotency_key", nullable = true, length = 128)
    var idempotencyKey: String?,

    @Column(name = "failure_reason", nullable = true, length = 500)
    var failureReason: String?,
) {
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
}
