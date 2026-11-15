package ai.rojan.backend.api.payment

import ai.rojan.backend.api.common.ApiError
import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.application.payment.GetPaymentTransactionUseCase
import ai.rojan.backend.application.payment.HandlePaymentCallbackCommand
import ai.rojan.backend.application.payment.HandlePaymentCallbackUseCase
import ai.rojan.backend.application.payment.InitiatePaymentCommand
import ai.rojan.backend.application.payment.InitiatePaymentUseCase
import ai.rojan.backend.domain.booking.BookingId
import ai.rojan.backend.domain.payment.PaymentTransaction
import ai.rojan.backend.domain.payment.PaymentTransactionId
import ai.rojan.backend.domain.payment.PaymentTransactionMethod
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.util.UUID

/**
 * Payment Gateway Step 5. Wires exactly the three read/write Step 4 use cases - [InitiatePaymentUseCase],
 * [HandlePaymentCallbackUseCase], [GetPaymentTransactionUseCase] - to HTTP; [ai.rojan.backend.application.payment.ExpirePendingPaymentsUseCase]
 * has no endpoint (it's a scheduled/system operation, out of this step's scope).
 */
@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments")
class PaymentController(
    private val initiatePaymentUseCase: InitiatePaymentUseCase,
    private val handlePaymentCallbackUseCase: HandlePaymentCallbackUseCase,
    private val getPaymentTransactionUseCase: GetPaymentTransactionUseCase,
    private val currentUserResolver: CurrentUserResolver,
) {

    @PostMapping
    @Operation(summary = "Initiate an online-gateway payment for a pending booking as the authenticated customer")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Payment initiated (or the identical result of a replayed idempotency key)"),
        ApiResponse(
            responseCode = "409",
            description = "The booking is not eligible for payment, or the idempotency key belongs to a different customer",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "The booking does not exist",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(responseCode = "403", description = "The booking belongs to a different customer"),
    )
    @Transactional
    fun initiate(
        @Valid @RequestBody request: InitiatePaymentRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): ResponseEntity<InitiatePaymentResponse> {
        require(request.method == PaymentTransactionMethod.ONLINE_GATEWAY) {
            "Only ONLINE_GATEWAY payments are supported by this endpoint, was ${request.method}"
        }
        val customerId = currentUserResolver.resolve(principal)

        val initiation = initiatePaymentUseCase.execute(
            InitiatePaymentCommand(
                customerId = customerId,
                bookingId = BookingId(request.bookingId),
                idempotencyKey = request.idempotencyKey,
                callbackUrl = callbackUrl(),
            ),
        )
        // The transaction was just created/fetched for this exact caller by InitiatePaymentUseCase
        // itself, so this read-back can never be denied - it only exists to assemble the response
        // fields (status/amount/currency/provider) InitiatePaymentResult doesn't carry, without
        // touching that use case's return type.
        val transaction = getPaymentTransactionUseCase.execute(initiation.transactionId, customerId)

        return ResponseEntity.status(HttpStatus.CREATED).body(
            InitiatePaymentResponse(
                transactionId = initiation.transactionId.value,
                status = transaction.status,
                amount = transaction.amount,
                currency = transaction.currency,
                provider = transaction.gatewayProvider,
                authority = initiation.authority,
                redirectUrl = initiation.redirectUrl,
            ),
        )
    }

    private fun callbackUrl(): String =
        ServletUriComponentsBuilder.fromCurrentContextPath().path("/api/v1/payments/callback/zarinpal").toUriString()

    @GetMapping("/callback/zarinpal")
    @Operation(
        summary = "Zarinpal's own browser redirect after a payment attempt - public, never trusts the inbound Status",
        description = "Authority is the only input used to look up the transaction; the real outcome always comes " +
            "from HandlePaymentCallbackUseCase re-verifying with the gateway, never from the Status query parameter.",
    )
    @Transactional
    fun zarinpalCallback(
        @RequestParam("Authority") authority: String,
        @RequestParam("Status", required = false) status: String?,
    ): PaymentTransactionResponse {
        // [status] is accepted only because Zarinpal's real redirect always sends it - it is
        // deliberately never read. The authoritative outcome comes solely from
        // HandlePaymentCallbackUseCase's own call to PaymentGatewayPort.verify.
        val transaction = handlePaymentCallbackUseCase.execute(HandlePaymentCallbackCommand(authority))
        return transaction.toResponse()
    }

    @GetMapping("/{transactionId}")
    @Operation(summary = "Get a payment transaction (its customer, or the salon owner/a member with VIEW_PAYMENTS, only)")
    fun get(
        @PathVariable transactionId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ): PaymentTransactionResponse {
        val callerId = currentUserResolver.resolve(principal)
        val transaction = getPaymentTransactionUseCase.execute(PaymentTransactionId(transactionId), callerId)
        return transaction.toResponse()
    }

    private fun PaymentTransaction.toResponse() = PaymentTransactionResponse(
        transactionId = id.value,
        salonId = salonId.value,
        customerId = customerId.value,
        bookingId = bookingId?.value,
        method = method,
        status = status,
        amount = amount,
        currency = currency,
        provider = gatewayProvider,
        authority = gatewayAuthority,
        referenceId = gatewayReferenceId,
        failureReason = failureReason,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
