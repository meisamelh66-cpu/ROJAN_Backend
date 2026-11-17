package ai.rojan.backend.application.payment

import ai.rojan.backend.application.port.GatewayInitiationResult
import ai.rojan.backend.application.port.GatewayVerificationResult
import ai.rojan.backend.application.port.PaymentGatewayPort
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.booking.BookingId
import ai.rojan.backend.domain.booking.BookingRepository
import ai.rojan.backend.domain.booking.BookingStatus
import ai.rojan.backend.domain.common.BookingAccessDeniedException
import ai.rojan.backend.domain.common.BookingNotEligibleForPaymentException
import ai.rojan.backend.domain.common.BookingNotFoundException
import ai.rojan.backend.domain.common.IdempotencyKeyOwnedByAnotherCustomerException
import ai.rojan.backend.domain.common.PaymentTransactionNotFoundException
import ai.rojan.backend.domain.common.ServiceNotFoundException
import ai.rojan.backend.domain.common.UnknownPaymentAuthorityException
import ai.rojan.backend.domain.payment.PaymentStatus
import ai.rojan.backend.domain.payment.PaymentTransaction
import ai.rojan.backend.domain.payment.PaymentTransactionId
import ai.rojan.backend.domain.payment.PaymentTransactionMethod
import ai.rojan.backend.domain.payment.PaymentTransactionRepository
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.ServiceRepository
import ai.rojan.backend.domain.user.UserId
import java.time.Duration
import java.time.Instant

data class InitiatePaymentCommand(
    val customerId: UserId,
    val bookingId: BookingId,
    val idempotencyKey: String,
    val callbackUrl: String,
)

data class InitiatePaymentResult(
    val transactionId: PaymentTransactionId,
    val authority: String,
    val redirectUrl: String,
)

/**
 * Payment Gateway Step 4. [amount] is always the real, authoritative price read from the booking's
 * own [ai.rojan.backend.domain.salon.Service] — never a client-supplied value, matching
 * [CreateBookingUseCase]'s own "server computes `endTime` from the service's real duration, never
 * trusts a client-supplied one" precedent.
 *
 * Concurrency (duplicate initiate, same idempotency key): no advisory lock is needed for the
 * create-vs-create race — the real, DB-enforced unique index on `payment_transactions.idempotency_key`
 * (Step 2) is itself the atomic guard. The loser's [PaymentTransactionRepository.save] throws
 * (a `DataIntegrityViolationException` from the infrastructure layer — caught here as a plain
 * [RuntimeException] so this module, which has no Spring dependency at all, never needs to name that
 * Spring-specific type); the loser then re-reads the winner's row by the same key and proceeds through
 * the identical [completeInitiation] path the winner uses, so the real [PaymentGatewayPort.initiate]
 * call only ever happens once — [completeInitiation] is itself guarded by
 * [PaymentTransactionRepository.updateAtomically], so whichever of the two callers (not necessarily
 * the creation-race winner) reaches it first is the one that actually calls the gateway; the other
 * sees the already-[PaymentStatus.AWAITING_CALLBACK] row and skips straight to reading its real,
 * already-persisted authority back out.
 *
 * Cross-customer idempotency-key reuse: looked up before anything else; a different customer
 * presenting the same literal key is denied outright ([IdempotencyKeyOwnedByAnotherCustomerException])
 * - never replayed, never silently given an independent fresh transaction under the same key, keeping
 * the existing real, DB-enforced global uniqueness on that column meaningful.
 */
class InitiatePaymentUseCase(
    private val bookingRepository: BookingRepository,
    private val serviceRepository: ServiceRepository,
    private val paymentTransactionRepository: PaymentTransactionRepository,
    private val paymentGatewayPort: PaymentGatewayPort,
) {
    fun execute(command: InitiatePaymentCommand): InitiatePaymentResult {
        paymentTransactionRepository.findByIdempotencyKey(command.idempotencyKey)?.let { existing ->
            requireSameCustomer(existing, command.customerId, command.idempotencyKey)
            return completeInitiation(existing, "booking ${existing.bookingId?.value}", command.callbackUrl)
        }

        val booking = bookingRepository.findById(command.bookingId)
            ?: throw BookingNotFoundException(command.bookingId.value.toString())
        if (booking.customerId != command.customerId) {
            throw BookingAccessDeniedException(booking.id.value.toString())
        }
        if (booking.status != BookingStatus.PENDING) {
            throw BookingNotEligibleForPaymentException(booking.id.value.toString(), "booking status is ${booking.status}, not PENDING")
        }
        val service = serviceRepository.findById(booking.serviceId)
            ?: throw ServiceNotFoundException(booking.serviceId.value.toString())

        val transaction = PaymentTransaction.create(
            salonId = booking.salonId,
            customerId = command.customerId,
            bookingId = booking.id,
            amount = service.price.toLong(),
            method = PaymentTransactionMethod.ONLINE_GATEWAY,
            idempotencyKey = command.idempotencyKey,
        )

        val owned = try {
            paymentTransactionRepository.save(transaction)
        } catch (lostCreationRace: RuntimeException) {
            val winner = paymentTransactionRepository.findByIdempotencyKey(command.idempotencyKey) ?: throw lostCreationRace
            requireSameCustomer(winner, command.customerId, command.idempotencyKey)
            winner
        }

        return completeInitiation(owned, service.name, command.callbackUrl)
    }

    private fun requireSameCustomer(transaction: PaymentTransaction, customerId: UserId, idempotencyKey: String) {
        if (transaction.customerId != customerId) throw IdempotencyKeyOwnedByAnotherCustomerException(idempotencyKey)
    }

    /**
     * The real gateway call happens here, and only here — guarded by [PaymentTransactionRepository.updateAtomically]
     * so exactly one of any number of concurrent callers for the same [transaction] id ever actually
     * calls [PaymentGatewayPort.initiate]; every other caller sees the already-[PaymentStatus.AWAITING_CALLBACK]
     * row (real authority already persisted) and reconstructs the identical redirect URL via
     * [PaymentGatewayPort.redirectUrlFor] instead of calling the gateway a second time.
     */
    private fun completeInitiation(transaction: PaymentTransaction, description: String, callbackUrl: String): InitiatePaymentResult {
        var initiation: GatewayInitiationResult? = null
        val result = paymentTransactionRepository.updateAtomically(transaction.id) { current ->
            if (current.status == PaymentStatus.INITIATED) {
                initiation = paymentGatewayPort.initiate(current.amount, description, callbackUrl, current.salonId)
                current.markAwaitingCallback(gatewayProvider = paymentGatewayPort.providerName, gatewayAuthority = initiation!!.authority)
            }
        } ?: transaction

        val authority = result.gatewayAuthority ?: error("A transaction reaching completeInitiation must have a gatewayAuthority")
        return InitiatePaymentResult(
            transactionId = result.id,
            authority = authority,
            redirectUrl = initiation?.redirectUrl ?: paymentGatewayPort.redirectUrlFor(authority),
        )
    }
}

data class HandlePaymentCallbackCommand(val authority: String)

/**
 * Payment Gateway Step 4. Requirement 1 ("inbound browser callback trusted نیست") is honored by this
 * command carrying nothing but [authority] - no claimed status, no claimed amount from the browser
 * redirect is ever read; the only authoritative facts come from [PaymentGatewayPort.verify], called
 * with this backend's own stored [PaymentTransaction.amount], never a value from the request.
 */
class HandlePaymentCallbackUseCase(
    private val paymentTransactionRepository: PaymentTransactionRepository,
    private val paymentGatewayPort: PaymentGatewayPort,
) {
    fun execute(command: HandlePaymentCallbackCommand): PaymentTransaction {
        val existing = paymentTransactionRepository.findByGatewayAuthority(command.authority)
            ?: throw UnknownPaymentAuthorityException(command.authority)

        return paymentTransactionRepository.updateAtomically(existing.id) { current ->
            if (current.status != PaymentStatus.AWAITING_CALLBACK) return@updateAtomically
            current.markVerifying()
            when (val verification = paymentGatewayPort.verify(current.gatewayAuthority!!, current.amount, current.salonId)) {
                is GatewayVerificationResult.Success -> current.markSucceeded(verification.gatewayReferenceId)
                is GatewayVerificationResult.Failure -> current.markFailed(verification.reason)
            }
        } ?: existing
    }
}

/**
 * Payment Gateway Step 4 — mirrors [BookingController.get]'s own `requireCustomerOrOwner` shape
 * (and [CancelBookingUseCase]'s own internal, not-Controller-delegated ownership check), moved into
 * the Application layer since no Controller exists yet in this step. Returns the real domain
 * [PaymentTransaction] as-is — [PaymentTransaction.gatewayAuthority]/[PaymentTransaction.gatewayReferenceId]
 * are opaque ids, never a provider secret, so no separate response-shaping is needed to avoid leaking one.
 */
class GetPaymentTransactionUseCase(
    private val paymentTransactionRepository: PaymentTransactionRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(id: PaymentTransactionId, callerId: UserId): PaymentTransaction {
        val transaction = paymentTransactionRepository.findById(id)
            ?: throw PaymentTransactionNotFoundException(id.value.toString())
        if (transaction.customerId == callerId) return transaction
        salonPermissionResolver.require(transaction.salonId, callerId, Permission.VIEW_PAYMENTS)
        return transaction
    }
}

/**
 * Payment Gateway Step 4. No `expiresAt` column exists on `payment_transactions` (Step 2's real
 * schema) - [HOLD_DURATION] is a fixed, in-code policy compared against the already-persisted,
 * real [PaymentTransaction.createdAt], not a stored per-transaction deadline. Avoids a schema change
 * entirely while still implementing real, correct expiry - not a stub.
 *
 * Never calls [CancelBookingUseCase] (it has no "system caller" identity to present to its own
 * ownership check) - cancels the real, already-public [ai.rojan.backend.domain.booking.Booking.cancel]
 * domain method directly, the same real method that use case itself calls internally, then
 * [BookingRepository.save] - [CancelBookingUseCase] itself is never touched.
 */
class ExpirePendingPaymentsUseCase(
    private val paymentTransactionRepository: PaymentTransactionRepository,
    private val bookingRepository: BookingRepository,
    private val now: () -> Instant = Instant::now,
) {
    fun execute(): List<PaymentTransaction> {
        val cutoff = now().minus(HOLD_DURATION)
        return paymentTransactionRepository.findExpirable(cutoff).mapNotNull { expirableCandidate ->
            val updated = paymentTransactionRepository.updateAtomically(expirableCandidate.id) { current ->
                if (current.status == PaymentStatus.INITIATED || current.status == PaymentStatus.AWAITING_CALLBACK) {
                    current.markExpired()
                }
            } ?: return@mapNotNull null

            if (updated.status == PaymentStatus.EXPIRED) {
                cancelAssociatedBookingIfStillPending(updated)
            }
            updated
        }
    }

    private fun cancelAssociatedBookingIfStillPending(transaction: PaymentTransaction) {
        val bookingId = transaction.bookingId ?: return
        val booking = bookingRepository.findById(bookingId) ?: return
        if (booking.status == BookingStatus.PENDING) {
            booking.cancel()
            bookingRepository.save(booking)
        }
    }

    companion object {
        val HOLD_DURATION: Duration = Duration.ofMinutes(15)
    }
}
