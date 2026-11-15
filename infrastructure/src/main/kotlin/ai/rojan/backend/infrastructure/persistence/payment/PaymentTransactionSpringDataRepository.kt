package ai.rojan.backend.infrastructure.persistence.payment

import ai.rojan.backend.domain.payment.PaymentStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface PaymentTransactionSpringDataRepository : JpaRepository<PaymentTransactionJpaEntity, UUID> {
    fun findByGatewayAuthority(gatewayAuthority: String): PaymentTransactionJpaEntity?
    fun findByIdempotencyKey(idempotencyKey: String): PaymentTransactionJpaEntity?
    fun findByStatusInAndCreatedAtBefore(statuses: List<PaymentStatus>, createdBefore: Instant): List<PaymentTransactionJpaEntity>
}
