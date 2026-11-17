package ai.rojan.backend.infrastructure.payment

import ai.rojan.backend.application.port.GatewayInitiationResult
import ai.rojan.backend.application.port.GatewayTestResult
import ai.rojan.backend.application.port.GatewayVerificationResult
import ai.rojan.backend.application.port.PaymentGatewayPort
import ai.rojan.backend.domain.salon.SalonId
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test-only [PaymentGatewayPort] placeholder — no real HTTP call, no Zarinpal dependency, same role
 * [LoggingSmsProvider] already plays for `SmsProviderPort`. Deterministic by construction: [initiate]
 * hands out `FAKE-AUTH-<n>` from a simple incrementing counter (no randomness anywhere), and [verify]
 * succeeds by default with a `FAKE-REF-<authority>` reference unless a test has explicitly scripted
 * that authority to fail via [scriptVerificationFailure].
 *
 * Payment Gateway Step 4: [verify] now also records the amount each [authority] was really [initiate]d
 * with and compares it against the amount [verify] is called with — a real Zarinpal-shaped behavior
 * (the gateway itself would reject a mismatched amount), needed so `HandlePaymentCallbackUseCase`'s
 * "amount mismatch never succeeds" requirement has something genuine to exercise instead of an always-
 * agreeing fake.
 *
 * P1 Per-Salon Payment Gateway Configuration: this fake deliberately does NOT look up
 * [ai.rojan.backend.domain.payment.PaymentGatewayConfigRepository] at all — per-salon credential
 * *resolution* is the real [ZarinpalPaymentGatewayAdapter]'s own concern (covered by
 * `ZarinpalPaymentGatewayAdapterTest`/`PaymentGatewayConfigRepositoryAdapterTest`), not the booking/
 * payment-flow tests this fake exists for. [lastInitiateSalonId]/[lastVerifySalonId] merely record
 * which [SalonId] each call was made *for*, so a flow test can assert the right salon's transaction
 * drove the call without this fake needing to know anything about credentials.
 */
@Component
@Profile("test")
final class FakePaymentGatewayAdapter : PaymentGatewayPort {

    override val providerName: String = "FAKE"

    private val counter = AtomicInteger(0)
    private val scriptedFailures = ConcurrentHashMap<String, String>()
    private val initiatedAmounts = ConcurrentHashMap<String, Long>()
    private val initiateCalls = AtomicInteger(0)
    private val verifyCalls = AtomicInteger(0)

    var lastInitiateAmount: Long? = null
        private set
    var lastInitiateCallbackUrl: String? = null
        private set
    var lastInitiateSalonId: SalonId? = null
        private set
    var lastVerifyAuthority: String? = null
        private set
    var lastVerifyAmount: Long? = null
        private set
    var lastVerifySalonId: SalonId? = null
        private set

    /** Payment Gateway Step 4 — real, thread-safe call counts, used by `PaymentConcurrencyIntegrationTest` to prove a race produced exactly one real gateway call, not merely one final persisted state. */
    val initiateCallCount: Int get() = initiateCalls.get()
    val verifyCallCount: Int get() = verifyCalls.get()

    override fun initiate(amount: Long, description: String, callbackUrl: String, salonId: SalonId): GatewayInitiationResult {
        initiateCalls.incrementAndGet()
        lastInitiateAmount = amount
        lastInitiateCallbackUrl = callbackUrl
        lastInitiateSalonId = salonId
        val authority = "FAKE-AUTH-${counter.incrementAndGet()}"
        initiatedAmounts[authority] = amount
        return GatewayInitiationResult(authority = authority, redirectUrl = redirectUrlFor(authority))
    }

    override fun verify(authority: String, amount: Long, salonId: SalonId): GatewayVerificationResult {
        verifyCalls.incrementAndGet()
        lastVerifyAuthority = authority
        lastVerifyAmount = amount
        lastVerifySalonId = salonId
        val scriptedReason = scriptedFailures[authority]
        val initiatedAmount = initiatedAmounts[authority]
        return when {
            scriptedReason != null -> GatewayVerificationResult.Failure(scriptedReason)
            initiatedAmount != null && initiatedAmount != amount ->
                GatewayVerificationResult.Failure("amount mismatch: initiated with $initiatedAmount, verify called with $amount")
            else -> GatewayVerificationResult.Success(gatewayReferenceId = "FAKE-REF-$authority")
        }
    }

    override fun redirectUrlFor(authority: String): String = "https://fake-gateway.test/pay/$authority"

    override fun testCredential(salonId: SalonId): GatewayTestResult = GatewayTestResult.Success

    /** Configures a specific, already-[initiate]d [authority] to fail [verify] with [reason] — every other authority still succeeds. */
    fun scriptVerificationFailure(authority: String, reason: String) {
        scriptedFailures[authority] = reason
    }
}
