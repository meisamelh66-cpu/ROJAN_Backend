package ai.rojan.backend.infrastructure.payment

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.BeanCreationException
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource

/**
 * Gap 2A (Production Infrastructure Readiness) - the fail-fast guarantee
 * [ZarinpalWiringIntegrationTest] explicitly noted it no longer covers now that the global
 * `ZARINPAL_MERCHANT_ID` is gone: this one real Spring context (not the full
 * [ai.rojan.backend.bootstrap.BackendApplication], same minimal-registration shape
 * [ZarinpalWiringIntegrationTest] already uses) proves [PaymentCredentialPropertiesConfig] - and
 * therefore the production `docker-compose.prod.yml`'s new, required `PAYMENT_CREDENTIAL_ENCRYPTION_KEY`
 * entry - actually matters: with the property unset, context refresh must fail loudly, never boot
 * with an unusable cipher.
 */
class PaymentCredentialPropertiesConfigTest {

    private var context: AnnotationConfigApplicationContext? = null

    @AfterEach
    fun tearDown() {
        context?.close()
    }

    @Test
    fun `refresh fails loudly when rojan-payment-credential-encryption-key is unset`() {
        val ctx = AnnotationConfigApplicationContext()
        ctx.register(PaymentCredentialPropertiesConfig::class.java)

        assertThrows(BeanCreationException::class.java) { ctx.refresh() }
    }

    @Test
    fun `refresh succeeds and binds the configured key when rojan-payment-credential-encryption-key is set`() {
        val ctx = AnnotationConfigApplicationContext()
        ctx.environment.propertySources.addFirst(
            MapPropertySource("test-props", mapOf("rojan.payment.credential-encryption-key" to "a-clearly-fake-test-key-not-a-real-secret")),
        )
        ctx.register(PaymentCredentialPropertiesConfig::class.java)
        ctx.refresh()
        context = ctx

        val properties = ctx.getBean(PaymentCredentialProperties::class.java)
        assertEquals("a-clearly-fake-test-key-not-a-real-secret", properties.credentialEncryptionKey)
    }
}
