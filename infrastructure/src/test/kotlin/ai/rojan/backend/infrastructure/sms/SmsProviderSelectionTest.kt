package ai.rojan.backend.infrastructure.sms

import ai.rojan.backend.application.port.SmsProviderPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * Validates the bean-selection design actually behaves as designed - the
 * one piece of that design with real Spring-wiring risk, so it gets its
 * own explicit test rather than being assumed correct from reasoning
 * alone. Uses [ApplicationContextRunner] (lightweight, no real
 * `DataSource`/Redis/full application context needed) rather than
 * `@SpringBootTest`, since this only needs to exercise SMS-provider
 * wiring in isolation.
 *
 * No active profile is set in either test - `@Profile("!test")` on all
 * four classes under test is satisfied trivially by the runner's default
 * (empty) active-profile set, the same way it's satisfied in any real
 * non-test deployment.
 */
class SmsProviderSelectionTest {

    private val baseContextRunner = ApplicationContextRunner()
        .withUserConfiguration(
            SmsPropertiesConfig::class.java,
            RealSmsProviderAdapter::class.java,
            MeliPayamakSharedPatternProviderConfig::class.java,
            MeliPayamakSharedPatternProvider::class.java,
        )
        .withPropertyValues(
            "rojan.sms.api-url=https://console.melipayamak.com/api/send/simple",
            "rojan.sms.api-key=test-api-key",
            "rojan.sms.sender=ROJANAI",
        )

    @Test
    fun `with rojan sms provider unset, RealSmsProviderAdapter is the sole SmsProviderPort bean`() {
        baseContextRunner.run { context ->
            val beanNames = context.getBeanNamesForType(SmsProviderPort::class.java)
            assertEquals(1, beanNames.size, "expected exactly one SmsProviderPort bean, found: ${beanNames.toList()}")
            assertInstanceOf(RealSmsProviderAdapter::class.java, context.getBean(SmsProviderPort::class.java))
        }
    }

    @Test
    fun `with rojan sms provider set to melipayamak-shared, MeliPayamakSharedPatternProvider is selected via @Primary`() {
        baseContextRunner
            .withPropertyValues(
                "rojan.sms.provider=melipayamak-shared",
                "rojan.sms.melipayamak.api-key=test-api-key",
                "rojan.sms.melipayamak.body-id=254",
            )
            .run { context ->
                // Both beans genuinely exist simultaneously here - proving the
                // @Primary tie-break was actually exercised, not that only one
                // candidate happened to be registered.
                val beanNames = context.getBeanNamesForType(SmsProviderPort::class.java)
                assertEquals(2, beanNames.size, "expected both adapters registered simultaneously, found: ${beanNames.toList()}")

                assertInstanceOf(MeliPayamakSharedPatternProvider::class.java, context.getBean(SmsProviderPort::class.java))
            }
    }
}
