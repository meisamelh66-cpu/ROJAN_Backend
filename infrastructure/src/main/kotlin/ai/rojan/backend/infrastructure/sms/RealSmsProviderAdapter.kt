package ai.rojan.backend.infrastructure.sms

import ai.rojan.backend.application.port.SmsProviderPort
import ai.rojan.backend.domain.auth.PhoneNumber
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * SMS Provider Integration: the real [SmsProviderPort] adapter, replacing
 * [LoggingSmsProvider] for every profile except `test` (see that class's
 * own doc comment for why the split exists). Talks to MelliPayamak's
 * API-key console REST endpoint - `POST {apiUrl}/{apiKey}` with a JSON
 * body of `{from, to, text}`, the API key passed as a URL path segment
 * (MelliPayamak's own convention, not a header - confirmed against their
 * official Node.js client, since their public docs don't spell out the
 * wire format directly). Every credential comes from [SmsProperties]
 * (itself sourced from `SMS_API_URL`/`SMS_API_KEY`/`SMS_SENDER` env vars) -
 * nothing here is a hardcoded secret. Uses the JDK's built-in
 * `java.net.http.HttpClient` rather than adding a new HTTP-client Gradle
 * dependency to `infrastructure` for one outbound call.
 */
@Component
@Profile("!test")
class RealSmsProviderAdapter(
    private val smsProperties: SmsProperties,
) : SmsProviderPort {

    private val log = LoggerFactory.getLogger(RealSmsProviderAdapter::class.java)
    private val objectMapper = ObjectMapper()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    override fun send(phoneNumber: PhoneNumber, message: String) {
        val endpoint = "${smsProperties.apiUrl.trimEnd('/')}/${smsProperties.apiKey}"
        val body = objectMapper.writeValueAsString(
            SendSmsRequest(from = smsProperties.sender, to = phoneNumber.value, text = message),
        )

        val request = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (ex: java.io.IOException) {
            throw SmsDeliveryException("Failed to reach the SMS provider for ${phoneNumber.value}", ex)
        }

        if (response.statusCode() !in 200..299) {
            throw SmsDeliveryException("SMS provider rejected the request for ${phoneNumber.value}: HTTP ${response.statusCode()} - ${response.body()}")
        }

        val result = runCatching { objectMapper.readValue(response.body(), SendSmsResponse::class.java) }
            .getOrElse { throw SmsDeliveryException("SMS provider returned an unparseable response for ${phoneNumber.value}: ${response.body()}") }

        if (result.recId.isNullOrBlank()) {
            throw SmsDeliveryException("SMS provider did not confirm delivery for ${phoneNumber.value}: ${result.status ?: "no recId in response"}")
        }

        log.info("SMS sent to {} (recId={})", phoneNumber.value, result.recId)
    }

    private data class SendSmsRequest(val from: String, val to: String, val text: String)

    private data class SendSmsResponse(val recId: String? = null, val status: String? = null)
}

/** Thrown when the real SMS provider rejects a request or is unreachable - never caught specially by [ai.rojan.backend.application.auth.RequestOtpUseCase] (keeping the OTP flow unchanged per this task's own scope), so it surfaces via `GlobalExceptionHandler`'s existing catch-all as a 500, logged against a traceId same as any other unexpected failure. */
class SmsDeliveryException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
