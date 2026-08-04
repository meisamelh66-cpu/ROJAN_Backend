package ai.rojan.backend.infrastructure.sms

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * SMS Provider Integration: MelliPayamak's API-key console REST endpoint
 * (https://console.melipayamak.com/api/send/simple/{apiKey}). No defaults
 * on purpose, same reasoning as `infrastructure.security.JwtProperties`'
 * own doc comment: startup must fail loudly if these are unset in any
 * profile other than `test` (see [RealSmsProviderAdapter], which is the
 * only consumer, and is itself `@Profile("!test")`) rather than silently
 * booting with an unusable SMS integration.
 */
@ConfigurationProperties(prefix = "rojan.sms")
data class SmsProperties(
    /** The vendor's send endpoint, without the API key appended - e.g. https://console.melipayamak.com/api/send/simple. [RealSmsProviderAdapter] appends `/{apiKey}` itself. */
    val apiUrl: String,
    val apiKey: String,
    /** The approved sender line/number MelliPayamak sends from - the JSON body's `from` field. */
    val sender: String,
)
