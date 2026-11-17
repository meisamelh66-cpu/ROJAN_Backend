package ai.rojan.backend.api.payment

import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.application.payment.ActivatePaymentGatewayConfigCommand
import ai.rojan.backend.application.payment.ActivatePaymentGatewayConfigUseCase
import ai.rojan.backend.application.payment.DeactivatePaymentGatewayConfigCommand
import ai.rojan.backend.application.payment.DeactivatePaymentGatewayConfigUseCase
import ai.rojan.backend.application.payment.GetPaymentGatewayConfigQuery
import ai.rojan.backend.application.payment.GetPaymentGatewayConfigUseCase
import ai.rojan.backend.application.payment.SavePaymentGatewayConfigCommand
import ai.rojan.backend.application.payment.SavePaymentGatewayConfigUseCase
import ai.rojan.backend.application.payment.TestPaymentGatewayConfigCommand
import ai.rojan.backend.application.payment.TestPaymentGatewayConfigUseCase
import ai.rojan.backend.application.port.GatewayTestResult
import ai.rojan.backend.domain.payment.PaymentGatewayConfig
import ai.rojan.backend.domain.salon.SalonId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * P1 Per-Salon Payment Gateway Configuration. Every endpoint here enforces ownership entirely
 * through [ai.rojan.backend.application.payment.PaymentGatewayConfigUseCases]' own
 * `requireManageAccess` (never a bare `request.salonId == callerId` shape in this controller) - a
 * caller without [ai.rojan.backend.domain.salon.Permission.MANAGE_PAYMENT_GATEWAY] at [salonId]
 * always gets a 403 from `GlobalExceptionHandler`'s existing [ai.rojan.backend.domain.common.SalonAccessDeniedException]
 * mapping, regardless of which other salon they themselves own.
 *
 * [get] never returns the real merchant id or its ciphertext - see [PaymentGatewayConfigResponse]'s
 * own doc comment. [save] accepts the real plaintext credential but never echoes it back.
 */
@RestController
@RequestMapping("/api/v1/salons/{salonId}/payment-gateway")
@Tag(name = "Payment Gateway Configuration")
class PaymentGatewayConfigController(
    private val savePaymentGatewayConfigUseCase: SavePaymentGatewayConfigUseCase,
    private val getPaymentGatewayConfigUseCase: GetPaymentGatewayConfigUseCase,
    private val activatePaymentGatewayConfigUseCase: ActivatePaymentGatewayConfigUseCase,
    private val deactivatePaymentGatewayConfigUseCase: DeactivatePaymentGatewayConfigUseCase,
    private val testPaymentGatewayConfigUseCase: TestPaymentGatewayConfigUseCase,
    private val currentUserResolver: CurrentUserResolver,
) {

    @GetMapping
    @Operation(summary = "Get a salon's own payment gateway configuration (owner/manager - MANAGE_PAYMENT_GATEWAY) - never returns the real merchant id")
    fun get(
        @PathVariable salonId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ): PaymentGatewayConfigResponse {
        val callerId = currentUserResolver.resolve(principal)
        val config = getPaymentGatewayConfigUseCase.execute(GetPaymentGatewayConfigQuery(SalonId(salonId), callerId))
        return config.toResponse()
    }

    @PutMapping
    @Operation(summary = "Save (create or replace) a salon's own payment gateway credential (owner/manager - MANAGE_PAYMENT_GATEWAY) - encrypted at rest, never echoed back")
    fun save(
        @PathVariable salonId: UUID,
        @Valid @RequestBody request: SavePaymentGatewayConfigRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): PaymentGatewayConfigResponse {
        val callerId = currentUserResolver.resolve(principal)
        val config = savePaymentGatewayConfigUseCase.execute(
            SavePaymentGatewayConfigCommand(
                salonId = SalonId(salonId),
                callerId = callerId,
                provider = request.provider,
                merchantId = request.merchantId,
            ),
        )
        return config.toResponse()
    }

    @PostMapping("/activate")
    @Operation(summary = "Activate a salon's already-saved payment gateway credential (owner/manager - MANAGE_PAYMENT_GATEWAY) - real payments only ever route through an active config")
    fun activate(
        @PathVariable salonId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ): PaymentGatewayConfigResponse {
        val callerId = currentUserResolver.resolve(principal)
        return activatePaymentGatewayConfigUseCase.execute(ActivatePaymentGatewayConfigCommand(SalonId(salonId), callerId)).toResponse()
    }

    @PostMapping("/deactivate")
    @Operation(summary = "Deactivate a salon's payment gateway credential (owner/manager - MANAGE_PAYMENT_GATEWAY) - stops routing real payments without discarding the saved credential")
    fun deactivate(
        @PathVariable salonId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ): PaymentGatewayConfigResponse {
        val callerId = currentUserResolver.resolve(principal)
        return deactivatePaymentGatewayConfigUseCase.execute(DeactivatePaymentGatewayConfigCommand(SalonId(salonId), callerId)).toResponse()
    }

    @PostMapping("/test")
    @Operation(summary = "Test a salon's currently-saved payment gateway credential against the real provider (owner/manager - MANAGE_PAYMENT_GATEWAY) - a minimal, non-persisting connectivity/validity probe, active or not")
    fun test(
        @PathVariable salonId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ): PaymentGatewayTestResponse {
        val callerId = currentUserResolver.resolve(principal)
        return when (val result = testPaymentGatewayConfigUseCase.execute(TestPaymentGatewayConfigCommand(SalonId(salonId), callerId))) {
            is GatewayTestResult.Success -> PaymentGatewayTestResponse(success = true, reason = null)
            is GatewayTestResult.Failure -> PaymentGatewayTestResponse(success = false, reason = result.reason)
        }
    }

    private fun PaymentGatewayConfig?.toResponse(): PaymentGatewayConfigResponse =
        if (this == null) {
            PaymentGatewayConfigResponse(configured = false, provider = null, isActive = false, maskedMerchantId = null, updatedAt = null)
        } else {
            PaymentGatewayConfigResponse(configured = true, provider = provider, isActive = isActive, maskedMerchantId = maskedMerchantId, updatedAt = updatedAt)
        }
}
