package ai.rojan.backend.infrastructure.storage

import ai.rojan.backend.application.port.MediaStoragePort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * Validates the bean-selection design actually behaves as designed - same
 * reasoning and same [ApplicationContextRunner] approach as
 * [ai.rojan.backend.infrastructure.sms.SmsProviderSelectionTest], the
 * pattern this test mirrors directly (`@ConditionalOnProperty` +
 * `@Primary` swap-in, plain default with no annotation needed for the
 * tie-break). No active profile is set in either test -
 * `@Profile("!test")` on both classes under test is satisfied trivially by
 * the runner's default (empty) active-profile set, same as in any real
 * non-test deployment.
 */
class MediaStorageSelectionTest {

    private val baseContextRunner = ApplicationContextRunner()
        .withUserConfiguration(
            LocalMediaStorageConfig::class.java,
            LocalDiskMediaStorageAdapter::class.java,
            MediaStorageConfig::class.java,
            S3CompatibleMediaStorage::class.java,
        )
        .withPropertyValues(
            "rojan.storage.endpoint=http://localhost:9000",
            "rojan.storage.region=us-east-1",
            "rojan.storage.bucket=rojan-media",
            "rojan.storage.access-key=minioadmin",
            "rojan.storage.secret-key=minioadmin",
        )

    @Test
    fun `with rojan storage provider unset, LocalDiskMediaStorageAdapter is the sole MediaStoragePort bean`() {
        baseContextRunner.run { context ->
            val beanNames = context.getBeanNamesForType(MediaStoragePort::class.java)
            assertEquals(1, beanNames.size, "expected exactly one MediaStoragePort bean, found: ${beanNames.toList()}")
            assertInstanceOf(LocalDiskMediaStorageAdapter::class.java, context.getBean(MediaStoragePort::class.java))
        }
    }

    @Test
    fun `with rojan storage provider set to s3, S3CompatibleMediaStorage is selected via @Primary`() {
        baseContextRunner
            .withPropertyValues("rojan.storage.provider=s3")
            .run { context ->
                // Both beans genuinely exist simultaneously here - proving the
                // @Primary tie-break was actually exercised, not that only one
                // candidate happened to be registered.
                val beanNames = context.getBeanNamesForType(MediaStoragePort::class.java)
                assertEquals(2, beanNames.size, "expected both adapters registered simultaneously, found: ${beanNames.toList()}")

                assertInstanceOf(S3CompatibleMediaStorage::class.java, context.getBean(MediaStoragePort::class.java))
            }
    }
}
