package ai.rojan.backend.infrastructure.payment

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * P1 Per-Salon Payment Gateway Configuration: the AES-256-GCM key [CredentialCipher] encrypts/
 * decrypts every salon's stored merchant id with. [credentialEncryptionKey] has no default on
 * purpose, same fail-loud posture as [ZarinpalProperties]' own old `merchantId` field - startup must
 * fail loudly if it's unset, never silently boot with an unusable (or absent) cipher. Unlike
 * [ZarinpalProperties]/`SmsProperties`, this is bound in every profile including `test` - the
 * encryption/decryption itself is pure, local crypto with no external network dependency to fake,
 * so the test suite gets a real (but clearly fake, see `application-test.yml`) key instead of a
 * `@Profile`-gated skip.
 */
@ConfigurationProperties(prefix = "rojan.payment")
data class PaymentCredentialProperties(
    val credentialEncryptionKey: String,
)

@Configuration
@EnableConfigurationProperties(PaymentCredentialProperties::class)
class PaymentCredentialPropertiesConfig
