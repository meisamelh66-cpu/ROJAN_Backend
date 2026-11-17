package ai.rojan.backend.domain.payment

import ai.rojan.backend.domain.salon.SalonId

/** P1 Per-Salon Payment Gateway Configuration — one row per salon (MVP: at most one provider config per salon). */
interface PaymentGatewayConfigRepository {
    fun findBySalonId(salonId: SalonId): PaymentGatewayConfig?

    /** Upsert by [PaymentGatewayConfig.id] — same create-or-update shape as [PaymentTransactionRepository.save]. */
    fun save(config: PaymentGatewayConfig): PaymentGatewayConfig
}
