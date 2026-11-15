package ai.rojan.backend.domain.payment

import java.util.UUID

@JvmInline
value class PaymentTransactionId(val value: UUID) {
    companion object {
        fun new(): PaymentTransactionId = PaymentTransactionId(UUID.randomUUID())
    }
}
