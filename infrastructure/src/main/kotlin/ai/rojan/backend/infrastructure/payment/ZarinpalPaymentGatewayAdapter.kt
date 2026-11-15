package ai.rojan.backend.infrastructure.payment

import ai.rojan.backend.application.port.GatewayInitiationResult
import ai.rojan.backend.application.port.GatewayVerificationResult
import ai.rojan.backend.application.port.PaymentGatewayPort
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/**
 * Payment Gateway Step 6 - the real [PaymentGatewayPort] adapter for Zarinpal's v4 REST API
 * (https://www.zarinpal.com/docs/paymentGateway/connectToGateway). Same role
 * `infrastructure.sms.RealSmsProviderAdapter` already plays for MelliPayamak: no Zarinpal-specific
 * type ever crosses this class's boundary - [initiate]/[verify] take and return only this codebase's
 * own [PaymentGatewayPort] types, and every genuine transport/parsing/rejection failure is wrapped in
 * [PaymentGatewayException] (never a raw [IOException], Jackson exception, or Zarinpal error code
 * reaching Application/Domain). Uses the JDK's own `java.net.http.HttpClient`, same as that SMS
 * adapter, rather than adding a new HTTP-client Gradle dependency for one outbound integration.
 *
 * Amount: Zarinpal's v4 `amount` field is Rial by default, with an optional `currency` field of
 * `"IRR"`/`"IRT"` - this backend's own [ai.rojan.backend.domain.payment.PaymentTransaction.amount] is
 * already an IRR-Rial `Long` (Payment Gateway Step 1's own design), so this is a direct 1:1
 * passthrough with no conversion - `currency: "IRR"` is sent explicitly on every request, never
 * omitted, to remove any ambiguity with the Toman-vs-Rial confusion real Zarinpal integrators
 * commonly hit.
 *
 * Success codes: both `100` (fresh success) and `101` (verify-only - "this authority was already
 * verified successfully") are real, documented Zarinpal successes, not a guess. Every other `code`
 * is a real rejection/failure, carrying Zarinpal's own `message` as the opaque
 * [GatewayVerificationResult.Failure.reason]/[PaymentGatewayException] text - never a provider-specific
 * code type leaking outward.
 *
 * Transport/parsing failures (HTTP error, timeout, malformed JSON) always throw [PaymentGatewayException]
 * rather than being folded into [GatewayVerificationResult.Failure] for [verify] - "we could not get a
 * straight answer from Zarinpal" is not the same fact as "Zarinpal told us no", and
 * `HandlePaymentCallbackUseCase` only has a terminal `markFailed` path for the latter. For [initiate]
 * there is no result type to express a failure at all ([GatewayInitiationResult] is not a
 * success/failure union), so every failure mode there throws unconditionally - `InitiatePaymentUseCase`
 * never persists an `AWAITING_CALLBACK` row for an authority that was never actually issued.
 */
@Component
@Profile("!test")
class ZarinpalPaymentGatewayAdapter(
    private val zarinpalProperties: ZarinpalProperties,
) : PaymentGatewayPort {

    override val providerName: String = "ZARINPAL"

    private val objectMapper = ObjectMapper()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(zarinpalProperties.timeoutSeconds))
        .build()

    override fun initiate(amount: Long, description: String, callbackUrl: String): GatewayInitiationResult {
        val root = postJson(
            path = "request.json",
            body = linkedMapOf(
                "merchant_id" to zarinpalProperties.merchantId,
                "amount" to amount,
                "currency" to CURRENCY,
                "callback_url" to callbackUrl,
                "description" to description,
            ),
        )
        val data = root.get("data")
        val code = data?.get("code")?.asInt(-1) ?: -1
        val authority = data?.get("authority")?.asText()
        if (!isSuccessCode(code) || authority.isNullOrBlank()) {
            throw PaymentGatewayException("Zarinpal rejected the payment initiation: ${describeFailure(root)}")
        }
        return GatewayInitiationResult(authority = authority, redirectUrl = redirectUrlFor(authority))
    }

    override fun verify(authority: String, amount: Long): GatewayVerificationResult {
        val root = postJson(
            path = "verify.json",
            body = linkedMapOf(
                "merchant_id" to zarinpalProperties.merchantId,
                "amount" to amount,
                "authority" to authority,
            ),
        )
        val data = root.get("data")
        val code = data?.get("code")?.asInt(-1) ?: -1
        if (!isSuccessCode(code)) {
            return GatewayVerificationResult.Failure(describeFailure(root))
        }
        val referenceId = data?.get("ref_id")?.asText().takeUnless { it.isNullOrBlank() } ?: "unknown"
        return GatewayVerificationResult.Success(gatewayReferenceId = referenceId)
    }

    override fun redirectUrlFor(authority: String): String =
        "${zarinpalProperties.startPayUrl.trimEnd('/')}/$authority"

    private fun isSuccessCode(code: Int) = code == CODE_SUCCESS || code == CODE_ALREADY_VERIFIED

    private fun describeFailure(root: JsonNode): String {
        val errorsNode = root.get("errors")?.takeIf { it.isObject }
        val code = root.get("data")?.get("code")?.takeIf { it.isInt }?.asInt()
            ?: errorsNode?.get("code")?.takeIf { it.isInt }?.asInt()
            ?: -1
        val message = root.get("data")?.get("message")?.asText()
            ?: errorsNode?.get("message")?.asText()
            ?: root.get("errors")?.toString()
            ?: "unknown error"
        return "code=$code message=$message"
    }

    private fun postJson(path: String, body: Map<String, Any>): JsonNode {
        val requestBody = objectMapper.writeValueAsString(body)
        val request = HttpRequest.newBuilder(URI.create("${zarinpalProperties.apiUrl.trimEnd('/')}/$path"))
            .timeout(Duration.ofSeconds(zarinpalProperties.timeoutSeconds))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (ex: HttpTimeoutException) {
            throw PaymentGatewayException("Zarinpal did not respond within ${zarinpalProperties.timeoutSeconds}s", ex)
        } catch (ex: IOException) {
            throw PaymentGatewayException("Failed to reach Zarinpal", ex)
        }

        if (response.statusCode() !in 200..299) {
            throw PaymentGatewayException("Zarinpal returned HTTP ${response.statusCode()}: ${response.body()}")
        }

        return try {
            objectMapper.readTree(response.body())
        } catch (ex: Exception) {
            throw PaymentGatewayException("Zarinpal returned an unparseable response: ${response.body()}", ex)
        }
    }

    private companion object {
        const val CURRENCY = "IRR"
        const val CODE_SUCCESS = 100
        const val CODE_ALREADY_VERIFIED = 101
    }
}

/**
 * Wraps every real Zarinpal transport/parsing/rejection failure - never a raw [IOException]/Jackson
 * exception/provider error code crosses this adapter's boundary. Uncaught anywhere specially, so it
 * surfaces via `GlobalExceptionHandler`'s existing catch-all as a 500, same as
 * `infrastructure.sms.SmsDeliveryException`.
 */
class PaymentGatewayException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
