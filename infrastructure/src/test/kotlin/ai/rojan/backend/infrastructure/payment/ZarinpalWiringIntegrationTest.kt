package ai.rojan.backend.infrastructure.payment

import ai.rojan.backend.application.port.CredentialEncryptionPort
import ai.rojan.backend.application.port.PaymentGatewayPort
import ai.rojan.backend.domain.payment.PaymentGatewayConfig
import ai.rojan.backend.domain.payment.PaymentGatewayConfigRepository
import ai.rojan.backend.domain.salon.SalonId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.MapPropertySource

/**
 * Payment Gateway Step 6 - a real Spring context (not the full [ai.rojan.backend.bootstrap.BackendApplication],
 * which would also need real JWT/SMS/DB config just to boot) proving the profile wiring itself: the
 * `test` profile must never see real Zarinpal, and a non-`test` profile must never fall back to the
 * fake. Registers exactly the classes this wiring decision depends on - [ZarinpalPropertiesConfig],
 * [ZarinpalPaymentGatewayAdapter], [FakePaymentGatewayAdapter], plus (P1 Per-Salon Payment Gateway
 * Configuration) a minimal [StubCollaborators] config for [ZarinpalPaymentGatewayAdapter]'s own
 * two new constructor dependencies - and nothing else.
 *
 * P1 Per-Salon Payment Gateway Configuration: the real production-boot fail-loud guarantee this file
 * used to cover for `ZARINPAL_MERCHANT_ID` is gone - that property no longer exists on
 * [ZarinpalProperties] at all (removed entirely; see that class's own doc comment). The equivalent
 * guarantee now belongs to `PAYMENT_CREDENTIAL_ENCRYPTION_KEY`/[PaymentCredentialProperties], a
 * separate concern from this file's own scope (profile wiring), not re-tested here.
 */
class ZarinpalWiringIntegrationTest {

    private var context: AnnotationConfigApplicationContext? = null

    @AfterEach
    fun tearDown() {
        context?.close()
    }

    @Test
    fun `the test profile wires the Fake gateway, never binds ZarinpalProperties, and never touches real Zarinpal`() {
        val ctx = buildContext(profile = "test")

        val port = ctx.getBean(PaymentGatewayPort::class.java)
        assertTrue(port is FakePaymentGatewayAdapter)
        assertEquals(0, ctx.getBeanNamesForType(ZarinpalProperties::class.java).size)
        assertEquals(0, ctx.getBeanNamesForType(ZarinpalPaymentGatewayAdapter::class.java).size)
    }

    @Test
    fun `a non-test profile wires the real Zarinpal adapter as the one PaymentGatewayPort bean, never the Fake`() {
        val ctx = buildContext(profile = "default")

        val port = ctx.getBean(PaymentGatewayPort::class.java)
        assertTrue(port is ZarinpalPaymentGatewayAdapter)
        assertEquals(0, ctx.getBeanNamesForType(FakePaymentGatewayAdapter::class.java).size)
    }

    private fun buildContext(profile: String): AnnotationConfigApplicationContext {
        val ctx = AnnotationConfigApplicationContext()
        ctx.environment.setActiveProfiles(profile)
        val properties = mapOf<String, Any>(
            "rojan.zarinpal.api-url" to "http://127.0.0.1:1",
            "rojan.zarinpal.start-pay-url" to "http://127.0.0.1:1/StartPay",
        )
        ctx.environment.propertySources.addFirst(MapPropertySource("test-props", properties))
        ctx.register(
            ZarinpalPropertiesConfig::class.java,
            ZarinpalPaymentGatewayAdapter::class.java,
            FakePaymentGatewayAdapter::class.java,
            StubCollaborators::class.java,
        )
        ctx.refresh()
        context = ctx
        return ctx
    }

    /** Stand-ins for [ZarinpalPaymentGatewayAdapter]'s two non-Zarinpal-specific constructor dependencies - this file's own scope is profile wiring, not credential resolution/encryption (each already covered by its own test). */
    @Configuration
    class StubCollaborators {
        @Bean
        fun paymentGatewayConfigRepository(): PaymentGatewayConfigRepository = object : PaymentGatewayConfigRepository {
            override fun findBySalonId(salonId: SalonId): PaymentGatewayConfig? = null
            override fun save(config: PaymentGatewayConfig): PaymentGatewayConfig = config
        }

        @Bean
        fun credentialEncryptionPort(): CredentialEncryptionPort = object : CredentialEncryptionPort {
            override fun encrypt(plaintext: String) = plaintext
            override fun decrypt(ciphertext: String) = ciphertext
        }
    }
}
