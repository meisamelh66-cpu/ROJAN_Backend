package ai.rojan.backend.infrastructure.persistence.payment

import ai.rojan.backend.domain.payment.PaymentGatewayProvider
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
@Table(name = "payment_gateway_configs")
@EntityListeners(AuditingEntityListener::class)
class PaymentGatewayConfigJpaEntity(
    @Id
    val id: UUID,

    @Column(name = "salon_id", nullable = false)
    var salonId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var provider: PaymentGatewayProvider,

    @Column(name = "encrypted_merchant_id", nullable = false)
    var encryptedMerchantId: String,

    @Column(name = "masked_merchant_id", nullable = false, length = 64)
    var maskedMerchantId: String,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean,
) {
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
}
