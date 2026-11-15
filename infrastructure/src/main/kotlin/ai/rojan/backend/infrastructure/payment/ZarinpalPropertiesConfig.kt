package ai.rojan.backend.infrastructure.payment

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * Payment Gateway Step 6: registers [ZarinpalProperties] only outside the `test` profile - mirrors
 * `infrastructure.sms.SmsPropertiesConfig` exactly. `@EnableConfigurationProperties` binds (and
 * validates) eagerly at context startup regardless of whether any bean actually consumes it, so this
 * must be profile-gated the same way [ZarinpalPaymentGatewayAdapter] is, or every test run would need
 * a real ZARINPAL_MERCHANT_ID just to boot the context.
 */
@Configuration
@Profile("!test")
@EnableConfigurationProperties(ZarinpalProperties::class)
class ZarinpalPropertiesConfig
