package ai.rojan.backend.domain.payment

/**
 * P1 Per-Salon Payment Gateway Configuration: which online-payment provider a
 * [PaymentGatewayConfig] holds a credential for. One real value today ([ZARINPAL], matching
 * [ai.rojan.backend.application.port.PaymentGatewayPort]'s own only real implementation) — adding a
 * second provider later means adding one more entry here plus one more [PaymentGatewayPort]
 * adapter, never a schema change (the column is a plain string).
 */
enum class PaymentGatewayProvider {
    ZARINPAL,
}
