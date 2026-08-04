package ai.rojan.backend.infrastructure.sms

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * SMS Provider Integration: registers [SmsProperties] only outside the
 * `test` profile - `@EnableConfigurationProperties` binds (and validates)
 * eagerly at context startup regardless of whether any bean actually
 * consumes it, so this must be profile-gated the same way
 * [RealSmsProviderAdapter] is, or every test run would need real
 * SMS_API_URL/SMS_API_KEY/SMS_SENDER values just to boot the context.
 */
@Configuration
@Profile("!test")
@EnableConfigurationProperties(SmsProperties::class)
class SmsPropertiesConfig
