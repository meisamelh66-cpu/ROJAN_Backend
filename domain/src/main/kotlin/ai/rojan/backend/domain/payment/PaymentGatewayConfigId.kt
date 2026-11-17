package ai.rojan.backend.domain.payment

import java.util.UUID

@JvmInline
value class PaymentGatewayConfigId(val value: UUID) {
    companion object {
        fun new(): PaymentGatewayConfigId = PaymentGatewayConfigId(UUID.randomUUID())
    }
}
