package ai.rojan.backend.infrastructure.storage

import ai.rojan.backend.application.port.MediaStoragePort
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Test-only placeholder - keeps uploaded bytes in memory instead of a real
 * S3-compatible bucket. [S3CompatibleMediaStorage] (its own doc comment)
 * is the real implementation for every other profile; this one is scoped
 * to `@Profile("test")` specifically so the test suite (and CI) never
 * needs a real MinIO/S3 endpoint reachable just to boot the Spring
 * context or run the media integration flow - same reasoning
 * [ai.rojan.backend.infrastructure.sms.LoggingSmsProvider] already
 * establishes for [ai.rojan.backend.application.port.SmsProviderPort].
 */
@Component
@Profile("test")
class InMemoryMediaStorage : MediaStoragePort {
    private val store = ConcurrentHashMap<String, ByteArray>()

    override fun upload(storageKey: String, content: ByteArray, contentType: String) {
        store[storageKey] = content
    }

    override fun delete(storageKey: String) {
        store.remove(storageKey)
    }

    override fun resolveUrl(storageKey: String): String = "https://cdn.test.rojan.ai/$storageKey"

    override fun resolveSignedUrl(storageKey: String, expirySeconds: Long): String =
        "https://cdn.test.rojan.ai/signed/$storageKey?expires=$expirySeconds"
}
