package ai.rojan.backend.domain.payment

import ai.rojan.backend.domain.booking.BookingId
import ai.rojan.backend.domain.common.InvalidPaymentStateException
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId
import java.time.Instant

/**
 * A single payment attempt against a gateway, independent of [ai.rojan.backend.domain.booking.Booking]
 * (Payment Gateway Step 1 design: no coupling - this aggregate never imports `BookingController`/
 * use-case types, only the `BookingId` value type to optionally associate itself with one).
 *
 * [amount] is `Long` minor-unit Rials (IRR has no practical fractional subunit, and Zarinpal's own
 * API already takes an integer Rial amount) - deliberately not `BigDecimal` (unlike
 * [ai.rojan.backend.domain.salon.Service.price]'s own choice for a different bounded context) and
 * never `Double`/`Float`, satisfying the "no floating-point money" requirement either way.
 *
 * [gatewayAuthority]/[gatewayReferenceId] are opaque strings this aggregate never parses or
 * validates the shape of - whatever the gateway adapter hands back. [idempotencyKey] is stored
 * as-is; uniqueness is a DB-layer concern (a future unique index), never enforced here.
 */
class PaymentTransaction private constructor(
    val id: PaymentTransactionId,
    val salonId: SalonId,
    val customerId: UserId,
    val bookingId: BookingId?,
    val method: PaymentTransactionMethod,
    val amount: Long,
    val currency: String,
    val idempotencyKey: String?,
    status: PaymentStatus,
    gatewayProvider: String?,
    gatewayAuthority: String?,
    gatewayReferenceId: String?,
    failureReason: String?,
    val createdAt: Instant,
    updatedAt: Instant,
) {
    var status: PaymentStatus = status
        private set

    var gatewayProvider: String? = gatewayProvider
        private set

    var gatewayAuthority: String? = gatewayAuthority
        private set

    var gatewayReferenceId: String? = gatewayReferenceId
        private set

    var failureReason: String? = failureReason
        private set

    var updatedAt: Instant = updatedAt
        private set

    fun markAwaitingCallback(gatewayProvider: String, gatewayAuthority: String) {
        requireStatusIn(setOf(PaymentStatus.INITIATED), "moved to AWAITING_CALLBACK")
        this.gatewayProvider = gatewayProvider
        this.gatewayAuthority = gatewayAuthority
        status = PaymentStatus.AWAITING_CALLBACK
        updatedAt = Instant.now()
    }

    fun markVerifying() {
        requireStatusIn(setOf(PaymentStatus.AWAITING_CALLBACK), "moved to VERIFYING")
        status = PaymentStatus.VERIFYING
        updatedAt = Instant.now()
    }

    fun markSucceeded(gatewayReferenceId: String) {
        requireStatusIn(setOf(PaymentStatus.VERIFYING), "moved to SUCCEEDED")
        this.gatewayReferenceId = gatewayReferenceId
        status = PaymentStatus.SUCCEEDED
        updatedAt = Instant.now()
    }

    fun markFailed(reason: String) {
        requireStatusIn(setOf(PaymentStatus.INITIATED, PaymentStatus.AWAITING_CALLBACK, PaymentStatus.VERIFYING), "moved to FAILED")
        failureReason = reason
        status = PaymentStatus.FAILED
        updatedAt = Instant.now()
    }

    fun markExpired() {
        requireStatusIn(setOf(PaymentStatus.INITIATED, PaymentStatus.AWAITING_CALLBACK), "moved to EXPIRED")
        status = PaymentStatus.EXPIRED
        updatedAt = Instant.now()
    }

    fun markRefunded() {
        requireStatusIn(setOf(PaymentStatus.SUCCEEDED), "moved to REFUNDED")
        status = PaymentStatus.REFUNDED
        updatedAt = Instant.now()
    }

    private fun requireStatusIn(validSources: Set<PaymentStatus>, action: String) {
        if (status !in validSources) {
            throw InvalidPaymentStateException("A transaction in status $status cannot be $action")
        }
    }

    companion object {
        fun create(
            salonId: SalonId,
            customerId: UserId,
            bookingId: BookingId?,
            amount: Long,
            currency: String = "IRR",
            method: PaymentTransactionMethod,
            idempotencyKey: String?,
        ): PaymentTransaction {
            require(amount > 0) { "Payment amount must be positive" }
            require(currency == "IRR") { "Only IRR is supported in this MVP" }
            val now = Instant.now()
            return PaymentTransaction(
                id = PaymentTransactionId.new(),
                salonId = salonId,
                customerId = customerId,
                bookingId = bookingId,
                method = method,
                amount = amount,
                currency = currency,
                idempotencyKey = idempotencyKey,
                status = PaymentStatus.INITIATED,
                gatewayProvider = null,
                gatewayAuthority = null,
                gatewayReferenceId = null,
                failureReason = null,
                createdAt = now,
                updatedAt = now,
            )
        }

        /**
         * Rehydrates an already-persisted transaction with its real, current field values - bypasses
         * [create]'s invariants (already satisfied when the row was first written), mirrors
         * [ai.rojan.backend.domain.booking.Booking.reconstitute]'s identical role for persistence
         * mapping. Never used to construct a brand-new transaction.
         */
        fun reconstitute(
            id: PaymentTransactionId,
            salonId: SalonId,
            customerId: UserId,
            bookingId: BookingId?,
            method: PaymentTransactionMethod,
            amount: Long,
            currency: String,
            idempotencyKey: String?,
            status: PaymentStatus,
            gatewayProvider: String?,
            gatewayAuthority: String?,
            gatewayReferenceId: String?,
            failureReason: String?,
            createdAt: Instant,
            updatedAt: Instant,
        ): PaymentTransaction = PaymentTransaction(
            id, salonId, customerId, bookingId, method, amount, currency, idempotencyKey,
            status, gatewayProvider, gatewayAuthority, gatewayReferenceId, failureReason, createdAt, updatedAt,
        )
    }
}
