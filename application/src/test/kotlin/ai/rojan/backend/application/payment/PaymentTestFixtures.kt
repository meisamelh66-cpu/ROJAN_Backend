package ai.rojan.backend.application.payment

import ai.rojan.backend.application.port.GatewayInitiationResult
import ai.rojan.backend.application.port.GatewayTestResult
import ai.rojan.backend.application.port.GatewayVerificationResult
import ai.rojan.backend.application.port.PaymentGatewayPort
import ai.rojan.backend.domain.payment.PaymentTransaction
import ai.rojan.backend.domain.payment.PaymentTransactionId
import ai.rojan.backend.domain.payment.PaymentTransactionRepository
import ai.rojan.backend.domain.salon.SalonId
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single-threaded in-memory fake — same role [ai.rojan.backend.application.booking.InMemoryBookingRepository]'s
 * own doc comment already states for itself: true concurrency safety is exercised separately, against
 * the real database (`PaymentConcurrencyIntegrationTest`, `bootstrap` module), not here. [updateAtomically]
 * here is a plain find-transition-save with no real locking - correct for single-threaded unit tests,
 * never used to claim concurrency correctness.
 */
internal class InMemoryPaymentTransactionRepository : PaymentTransactionRepository {
    private val store = mutableMapOf<PaymentTransactionId, PaymentTransaction>()

    override fun save(transaction: PaymentTransaction): PaymentTransaction {
        val key = transaction.idempotencyKey
        if (key != null) {
            val conflictingOwner = store.values.find { it.idempotencyKey == key && it.id != transaction.id }
            if (conflictingOwner != null) throw IdempotencyKeyAlreadyUsedFakeException(key)
        }
        store[transaction.id] = transaction
        return transaction
    }

    override fun findById(id: PaymentTransactionId): PaymentTransaction? = store[id]

    override fun findByGatewayAuthority(gatewayAuthority: String): PaymentTransaction? =
        store.values.find { it.gatewayAuthority == gatewayAuthority }

    override fun findByIdempotencyKey(idempotencyKey: String): PaymentTransaction? =
        store.values.find { it.idempotencyKey == idempotencyKey }

    override fun findExpirable(createdBefore: Instant): List<PaymentTransaction> =
        store.values.filter {
            (it.status == ai.rojan.backend.domain.payment.PaymentStatus.INITIATED || it.status == ai.rojan.backend.domain.payment.PaymentStatus.AWAITING_CALLBACK) &&
                it.createdAt.isBefore(createdBefore)
        }

    override fun updateAtomically(id: PaymentTransactionId, transition: (PaymentTransaction) -> Unit): PaymentTransaction? {
        val current = store[id] ?: return null
        transition(current)
        store[id] = current
        return current
    }
}

/** Stands in for the real `DataIntegrityViolationException` a Postgres unique-index violation would surface as — [InitiatePaymentUseCase] only ever catches the generic [RuntimeException] supertype, so this fake exception exercises that same catch path without the Application module needing any Spring dependency. */
internal class IdempotencyKeyAlreadyUsedFakeException(key: String) : RuntimeException("idempotency key already used: $key")

/** Deterministic, scriptable fake — same shape as the real [ai.rojan.backend.infrastructure.payment.FakePaymentGatewayAdapter], reimplemented here so `application` module tests never depend on `infrastructure`. */
internal class ScriptedPaymentGatewayPort : PaymentGatewayPort {
    override val providerName: String = "SCRIPTED"
    private val counter = AtomicInteger(0)
    private val scriptedFailures = mutableMapOf<String, String>()
    private val initiatedAmounts = mutableMapOf<String, Long>()

    var initiateCallCount = 0
        private set

    var lastInitiateSalonId: SalonId? = null
        private set
    var lastVerifySalonId: SalonId? = null
        private set

    override fun initiate(amount: Long, description: String, callbackUrl: String, salonId: SalonId): GatewayInitiationResult {
        initiateCallCount++
        lastInitiateSalonId = salonId
        val authority = "SCRIPTED-AUTH-${counter.incrementAndGet()}"
        initiatedAmounts[authority] = amount
        return GatewayInitiationResult(authority, redirectUrlFor(authority))
    }

    override fun verify(authority: String, amount: Long, salonId: SalonId): GatewayVerificationResult {
        lastVerifySalonId = salonId
        val reason = scriptedFailures[authority]
        val initiatedAmount = initiatedAmounts[authority]
        return when {
            reason != null -> GatewayVerificationResult.Failure(reason)
            initiatedAmount != null && initiatedAmount != amount -> GatewayVerificationResult.Failure("amount mismatch")
            else -> GatewayVerificationResult.Success("SCRIPTED-REF-$authority")
        }
    }

    override fun redirectUrlFor(authority: String): String = "https://scripted-gateway.test/pay/$authority"

    override fun testCredential(salonId: SalonId): GatewayTestResult = GatewayTestResult.Success

    fun scriptVerificationFailure(authority: String, reason: String) {
        scriptedFailures[authority] = reason
    }
}
