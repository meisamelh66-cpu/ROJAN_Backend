package ai.rojan.backend.api.payment

import ai.rojan.backend.domain.payment.PaymentGatewayProvider
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.Instant

/** [merchantId] is the only plaintext credential this request carries - encrypted before it's ever persisted (see `SavePaymentGatewayConfigUseCase`'s own doc comment). Never logged, never echoed back as-is in any response. */
data class SavePaymentGatewayConfigRequest(
    @field:NotNull
    val provider: PaymentGatewayProvider,

    @field:NotBlank
    @field:Schema(description = "The real provider merchant id, plaintext in this request only - encrypted at rest, never returned by any endpoint")
    val merchantId: String,
)

/**
 * [configured] is `false` (every other field `null`) when the salon has never saved a config at all
 * - the "Empty / Not configured" UI state, not an error. [maskedMerchantId] is a one-way display
 * string (e.g. `"••••••3456"`) - this DTO, same as every layer above infrastructure, never carries
 * the real merchant id or its ciphertext.
 */
data class PaymentGatewayConfigResponse(
    val configured: Boolean,
    val provider: PaymentGatewayProvider?,
    val isActive: Boolean,
    val maskedMerchantId: String?,
    val updatedAt: Instant?,
)

data class PaymentGatewayTestResponse(
    val success: Boolean,
    @field:Schema(description = "Human-readable, logging-safe failure reason - never the gateway's raw response body or the merchant id - null when success is true")
    val reason: String?,
)
