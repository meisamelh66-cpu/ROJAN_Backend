package ai.rojan.backend.infrastructure.sms

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Config for [MeliPayamakSharedPatternProvider] — MeliPayamak's API-key
 * console "Shared Pattern" endpoint (`console.melipayamak.com/api/send/shared/{apiKey}`),
 * which relays a value into a pre-approved SMS template MeliPayamak already
 * holds server-side. Deliberately separate from [SmsProperties] (which
 * stays bound to [RealSmsProviderAdapter]'s `send/simple` product): the two
 * products share the API-key auth model but need different request bodies
 * ([SmsProperties] has no `bodyId`, this has no `sender`/`from` — the
 * template itself fixes the sender line).
 *
 * Only bound when [MeliPayamakSharedPatternProviderConfig]'s own
 * `@ConditionalOnProperty` matches (`rojan.sms.provider=melipayamak-shared`)
 * - a deployment that never opts into this provider never needs
 * `MELIPAYAMAK_API_KEY`/`MELIPAYAMAK_BODY_ID` set at all, mirroring
 * [SmsPropertiesConfig]'s existing conditional-binding precedent.
 */
@ConfigurationProperties(prefix = "rojan.sms.melipayamak")
data class MeliPayamakSharedPatternProperties(
    val apiKey: String,
    /** The pre-approved template's numeric id from the MeliPayamak console — the JSON body's `bodyId` field. */
    val bodyId: Long,
    /** The Shared Pattern endpoint, without the API key appended — [MeliPayamakSharedPatternProvider] appends `/{apiKey}` itself. */
    val apiUrl: String = "https://console.melipayamak.com/api/send/shared",
)
