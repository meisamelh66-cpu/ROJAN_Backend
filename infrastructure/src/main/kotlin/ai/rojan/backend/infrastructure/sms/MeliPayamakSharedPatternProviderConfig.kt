package ai.rojan.backend.infrastructure.sms

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * Registers [MeliPayamakSharedPatternProperties] only when this provider is
 * actually selected (`rojan.sms.provider=melipayamak-shared`) -
 * `@EnableConfigurationProperties` binds (and validates) eagerly at context
 * startup regardless of whether any bean consumes it, so without this same
 * guard every deployment would need `MELIPAYAMAK_API_KEY`/`MELIPAYAMAK_BODY_ID`
 * set just to boot, even ones that never opt into this provider. Mirrors
 * [SmsPropertiesConfig]'s identical, already-established pattern for
 * [RealSmsProviderAdapter]/[SmsProperties].
 */
@Configuration
@Profile("!test")
@ConditionalOnProperty(prefix = "rojan.sms", name = ["provider"], havingValue = "melipayamak-shared")
@EnableConfigurationProperties(MeliPayamakSharedPatternProperties::class)
class MeliPayamakSharedPatternProviderConfig
