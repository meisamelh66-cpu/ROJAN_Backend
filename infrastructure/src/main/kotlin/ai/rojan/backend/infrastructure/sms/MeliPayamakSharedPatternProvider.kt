package ai.rojan.backend.infrastructure.sms

import ai.rojan.backend.application.port.SmsProviderPort
import ai.rojan.backend.domain.auth.PhoneNumber
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.UUID

/**
 * [SmsProviderPort] implementation for MeliPayamak's "Shared Pattern" REST
 * API-key endpoint - `POST https://console.melipayamak.com/api/send/shared/{apiKey}`,
 * JSON body `{bodyId, to, args}`. ROJAN keeps generating, hashing, and
 * verifying the OTP exactly as before (see `RequestOtpUseCase`/
 * `VerifyOtpUseCase`, both untouched); this adapter's only job is hostname,
 * one already-approved template (`bodyId`) fills in `args[0]` with the code
 * ROJAN generated. Never calls MeliPayamak's `/api/send/otp` (which would
 * mean the vendor generates the code) - that endpoint is out of scope by
 * design, not merely unused.
 *
 * [SmsProviderPort.send] carries a fully-rendered message, not a bare code
 * - [extractCode] recovers the code [RequestOtpUseCase.execute] embedded in
 * it, the same disclosed trade-off the prior `SendOtp`-targeting adapter
 * used, kept here specifically so neither the port nor the use case need to
 * change. Fails loudly (never sends a garbled/missing arg) if that message
 * template ever changes without this regex being updated to match - see
 * [MeliPayamakSharedPatternProviderTest] for the drift-detection test.
 *
 * [Primary] only matters the one time both this and [RealSmsProviderAdapter]
 * are simultaneously registered (`rojan.sms.provider=melipayamak-shared`
 * set) - [RealSmsProviderAdapter] needs no corresponding annotation for
 * that tie-break to work, per Spring's own `@Primary` semantics. Never
 * registered at all ([ConditionalOnProperty], no `matchIfMissing`) unless
 * that property is explicitly set - swapping in a future vendor (Kavenegar,
 * SMS.ir, Twilio, ...) means adding one more such adapter, not touching
 * this one or anything above [SmsProviderPort].
 */
@Component
@Primary
@Profile("!test")
@ConditionalOnProperty(prefix = "rojan.sms", name = ["provider"], havingValue = "melipayamak-shared")
class MeliPayamakSharedPatternProvider(
    private val properties: MeliPayamakSharedPatternProperties,
) : SmsProviderPort {

    private val log = LoggerFactory.getLogger(MeliPayamakSharedPatternProvider::class.java)
    private val objectMapper = ObjectMapper()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    override fun send(phoneNumber: PhoneNumber, message: String) {
        val code = extractCode(message)
        val requestId = UUID.randomUUID().toString()
        val recipient = toLocalMobileFormat(phoneNumber)
        val endpoint = "${properties.apiUrl.trimEnd('/')}/${properties.apiKey}"
        val requestBody = objectMapper.writeValueAsString(
            SharedPatternRequest(bodyId = properties.bodyId, to = recipient, args = listOf(code)),
        )

        val request = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val startNanos = System.nanoTime()
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (ex: HttpTimeoutException) {
            logOutcome(requestId, recipient, status = null, elapsedMs(startNanos), "timeout")
            throw SmsDeliveryException("Timed out calling MeliPayamak Shared Pattern API (requestId=$requestId)", ex)
        } catch (ex: java.io.IOException) {
            logOutcome(requestId, recipient, status = null, elapsedMs(startNanos), "network-error")
            throw SmsDeliveryException("Failed to reach MeliPayamak Shared Pattern API (requestId=$requestId)", ex)
        }
        val elapsedMs = elapsedMs(startNanos)

        if (response.statusCode() !in 200..299) {
            logOutcome(requestId, recipient, response.statusCode(), elapsedMs, "http-error")
            throw SmsDeliveryException(
                "MeliPayamak Shared Pattern API rejected the request (requestId=$requestId): HTTP ${response.statusCode()} - ${response.body()}",
            )
        }

        val result = runCatching { objectMapper.readValue(response.body(), SharedPatternResponse::class.java) }
            .getOrElse {
                logOutcome(requestId, recipient, response.statusCode(), elapsedMs, "invalid-response")
                throw SmsDeliveryException("MeliPayamak Shared Pattern API returned an unparseable response (requestId=$requestId): ${response.body()}")
            }

        if (result.recId.isNullOrBlank()) {
            logOutcome(requestId, recipient, response.statusCode(), elapsedMs, "no-recid")
            throw SmsDeliveryException("MeliPayamak Shared Pattern API did not confirm delivery (requestId=$requestId): ${result.status ?: "no recId in response"}")
        }

        logOutcome(requestId, recipient, response.statusCode(), elapsedMs, "sent")
    }

    private fun logOutcome(requestId: String, recipient: String, status: Int?, elapsedMs: Long, outcome: String) {
        val statusText = status?.toString() ?: "n/a"
        if (outcome == "sent") {
            log.info(
                "provider={} requestId={} recipient={} status={} elapsedMs={} outcome={}",
                PROVIDER_NAME, requestId, recipient, statusText, elapsedMs, outcome,
            )
        } else {
            log.warn(
                "provider={} requestId={} recipient={} status={} elapsedMs={} outcome={}",
                PROVIDER_NAME, requestId, recipient, statusText, elapsedMs, outcome,
            )
        }
    }

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    /** MeliPayamak's Shared Pattern `to` field expects a local 11-digit mobile number (e.g. `09123456789`), not E.164 - [PhoneNumber] stores E.164 (e.g. `+989123456789`), so `+98` is swapped for a leading `0` here rather than changing the domain's own phone format. */
    private fun toLocalMobileFormat(phoneNumber: PhoneNumber): String {
        val value = phoneNumber.value
        return if (value.startsWith("+98")) "0${value.removePrefix("+98")}" else value
    }

    private fun extractCode(message: String): String =
        CODE_PATTERN.find(message)?.groupValues?.get(1)
            ?: throw SmsDeliveryException(
                "Could not extract an OTP code from the outgoing message - RequestOtpUseCase's message format " +
                    "may have changed without this adapter being updated to match",
            )

    private data class SharedPatternRequest(val bodyId: Long, val to: String, val args: List<String>)

    private data class SharedPatternResponse(val recId: String? = null, val status: String? = null)

    private companion object {
        const val PROVIDER_NAME = "melipayamak-shared"
        // Bounded to 4-8 digits rather than a fixed count — this adapter doesn't
        // (and shouldn't) know the configured OtpPolicy.codeLength; it only needs
        // to recover whatever numeric code RequestOtpUseCase embedded.
        val CODE_PATTERN = Regex("""verification code is (\d{4,8})""")
    }
}
