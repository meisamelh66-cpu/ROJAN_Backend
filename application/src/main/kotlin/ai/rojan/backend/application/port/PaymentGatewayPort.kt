package ai.rojan.backend.application.port

/**
 * Output port for online-payment gateway calls — deliberately vendor-agnostic, same role
 * [SmsProviderPort] already plays for SMS delivery. No provider-specific type (a JSON response
 * shape, an HTTP DTO, a Zarinpal field name) may appear here or in [GatewayInitiationResult]/
 * [GatewayVerificationResult] — only the real adapter implementing this interface (e.g. a future
 * `ZarinpalGatewayAdapter`) knows that shape. Swapping providers means adding one new adapter class
 * and one DI registration — nothing above this port changes.
 *
 * [amount] is `Long` minor-unit Rials everywhere this port is used, matching
 * [ai.rojan.backend.domain.payment.PaymentTransaction.amount]'s own representation — never a
 * floating-point type.
 */
interface PaymentGatewayPort {
    /** A short, stable, storable identifier for whichever provider this instance is (`"ZARINPAL"`, `"FAKE"`, ...) — lets a caller persist which gateway handled a transaction without hardcoding any provider's name itself. */
    val providerName: String

    /** Starts a new payment attempt. [callbackUrl] is where the gateway sends the paying customer's browser back to once they finish (or abandon) payment. */
    fun initiate(amount: Long, description: String, callbackUrl: String): GatewayInitiationResult

    /** Confirms whether a prior [initiate] attempt, identified by its own [authority], genuinely succeeded for exactly [amount] — the caller's own stored amount, never trusted from anywhere else. */
    fun verify(authority: String, amount: Long): GatewayVerificationResult

    /**
     * Payment Gateway Step 4 — a pure, no-network function of [authority] alone, used only to let a caller that
     * lost a concurrent [initiate] race (another caller's attempt already won and persisted the real authority)
     * reconstruct the identical redirect URL without a second real gateway call. Real-world fit, not a
     * convenience invented for this codebase: Zarinpal's own actual redirect URL genuinely is a fixed prefix
     * plus the authority alone (`https://www.zarinpal.com/pg/StartPay/{authority}`), so every real adapter can
     * implement this with zero network access, same as the fake.
     */
    fun redirectUrlFor(authority: String): String
}

/** [authority] is an opaque, gateway-issued token for this one attempt. [redirectUrl] is where the caller sends the customer's browser to actually pay. */
data class GatewayInitiationResult(
    val authority: String,
    val redirectUrl: String,
)

sealed interface GatewayVerificationResult {
    /** [gatewayReferenceId] is an opaque, gateway-issued receipt id — never parsed, only stored. */
    data class Success(val gatewayReferenceId: String) : GatewayVerificationResult

    /** [reason] is a human-readable, logging-safe description — never the gateway's raw response body. */
    data class Failure(val reason: String) : GatewayVerificationResult
}
