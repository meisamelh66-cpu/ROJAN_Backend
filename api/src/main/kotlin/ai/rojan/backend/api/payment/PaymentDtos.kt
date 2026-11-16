package ai.rojan.backend.api.payment

import ai.rojan.backend.domain.payment.PaymentStatus
import ai.rojan.backend.domain.payment.PaymentTransactionMethod
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.util.UUID

/**
 * [method] is validated against [PaymentTransactionMethod.ONLINE_GATEWAY] in the controller - the
 * real request shape carries it (so a future WALLET/PAY_AT_SALON online flow can reuse this DTO),
 * but [ai.rojan.backend.application.payment.InitiatePaymentCommand] has no `method` field at all:
 * [ai.rojan.backend.application.payment.InitiatePaymentUseCase] always creates an ONLINE_GATEWAY
 * transaction. Never amount - the real price always comes from the booking's own service, server-side.
 */
data class InitiatePaymentRequest(
    @field:NotNull
    val bookingId: UUID,

    @field:NotNull
    @field:Schema(description = "Only ONLINE_GATEWAY is accepted by this endpoint")
    val method: PaymentTransactionMethod,

    @field:NotBlank
    @field:Schema(description = "Caller-generated; replaying the same key for the same customer returns the original transaction instead of creating a duplicate")
    val idempotencyKey: String,
)

data class InitiatePaymentResponse(
    val transactionId: UUID,
    val status: PaymentStatus,
    val amount: Long,
    val currency: String,
    val provider: String?,
    val authority: String,
    val redirectUrl: String,
)

/** Shape returned by `GET /payments/{transactionId}` and the callback endpoint - the full transaction, as the domain object already holds it. */
data class PaymentTransactionResponse(
    val transactionId: UUID,
    val salonId: UUID,
    val customerId: UUID,
    val bookingId: UUID?,
    val method: PaymentTransactionMethod,
    val status: PaymentStatus,
    val amount: Long,
    val currency: String,
    val provider: String?,
    val authority: String?,
    val referenceId: String?,
    val failureReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
