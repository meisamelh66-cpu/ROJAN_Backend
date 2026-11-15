package ai.rojan.backend.infrastructure.payment

import ai.rojan.backend.application.port.PaymentGatewayPort
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.BeanCreationException
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource

/**
 * Payment Gateway Step 6 - a real Spring context (not the full [ai.rojan.backend.bootstrap.BackendApplication],
 * which would also need real JWT/SMS/DB config just to boot) proving the profile wiring itself: the
 * `test` profile must never see real Zarinpal, and a non-`test` profile must never fall back to the
 * fake. Registers exactly the three classes this wiring decision depends on - [ZarinpalPropertiesConfig],
 * [ZarinpalPaymentGatewayAdapter], [FakePaymentGatewayAdapter] - and nothing else.
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
        val ctx = buildContext(profile = "default", merchantId = "real-merchant-id")

        val port = ctx.getBean(PaymentGatewayPort::class.java)
        assertTrue(port is ZarinpalPaymentGatewayAdapter)
        assertEquals(0, ctx.getBeanNamesForType(FakePaymentGatewayAdapter::class.java).size)
    }

    @Test
    fun `a non-test profile fails to start loudly when ZARINPAL_MERCHANT_ID is unset, rather than booting with a broken integration`() {
        assertThrows(BeanCreationException::class.java) {
            buildContext(profile = "default", merchantId = null)
        }
    }

    private fun buildContext(profile: String, merchantId: String? = "test-merchant-id"): AnnotationConfigApplicationContext {
        val ctx = AnnotationConfigApplicationContext()
        ctx.environment.setActiveProfiles(profile)
        val properties = mutableMapOf<String, Any>(
            "rojan.zarinpal.api-url" to "http://127.0.0.1:1",
            "rojan.zarinpal.start-pay-url" to "http://127.0.0.1:1/StartPay",
        )
        merchantId?.let { properties["rojan.zarinpal.merchant-id"] = it }
        ctx.environment.propertySources.addFirst(MapPropertySource("test-props", properties))
        ctx.register(ZarinpalPropertiesConfig::class.java, ZarinpalPaymentGatewayAdapter::class.java, FakePaymentGatewayAdapter::class.java)
        ctx.refresh()
        context = ctx
        return ctx
    }
}
