package ai.rojan.backend.infrastructure.storage

import ai.rojan.backend.application.port.MediaStoragePort
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.time.Duration

/**
 * Speaks the S3 API via the AWS SDK, pointed at a MinIO endpoint today
 * ([MediaStorageProperties]). Nothing here is MinIO-specific: swapping to
 * real AWS S3 or another S3-compatible provider later is a config change
 * ([MediaStorageProperties.endpoint]), never a code change - the whole
 * point of going through [MediaStoragePort] rather than a MinIO-only
 * client. Like [InMemoryMediaStorage], excluded from the `test` profile
 * (this environment has no MinIO/Docker available for a real integration
 * test to hit).
 *
 * Not the default [MediaStoragePort] bean - production has no S3-compatible
 * object store deployed yet, so [LocalDiskMediaStorageAdapter] (its own doc
 * comment) is the default for every non-`test` profile instead. Only
 * registered, and only wins the tie via [Primary], when
 * `rojan.storage.provider=s3` is explicitly set - same
 * `@ConditionalOnProperty` + `@Primary` pattern
 * [ai.rojan.backend.infrastructure.sms.MeliPayamakSharedPatternProvider]
 * already establishes for swapping in a non-default provider without
 * touching the default's own class. Adopting this as the real default is
 * a future, separately-scoped decision (needs MinIO or equivalent actually
 * deployed first) - staying registered-but-dormant here means switching to
 * it later is exactly that config change, not a rewrite.
 */
@Component
@Primary
@Profile("!test")
@ConditionalOnProperty(prefix = "rojan.storage", name = ["provider"], havingValue = "s3")
class S3CompatibleMediaStorage(
    private val s3Client: S3Client,
    private val s3Presigner: S3Presigner,
    private val properties: MediaStorageProperties,
) : MediaStoragePort {

    override fun upload(storageKey: String, content: ByteArray, contentType: String) {
        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(properties.bucket)
                .key(storageKey)
                .contentType(contentType)
                .contentLength(content.size.toLong())
                .build(),
            RequestBody.fromBytes(content),
        )
    }

    override fun delete(storageKey: String) {
        s3Client.deleteObject(
            DeleteObjectRequest.builder()
                .bucket(properties.bucket)
                .key(storageKey)
                .build(),
        )
    }

    override fun resolveUrl(storageKey: String): String =
        "${properties.publicBaseUrl.trimEnd('/')}/$storageKey"

    override fun resolveSignedUrl(storageKey: String, expirySeconds: Long): String {
        val getObjectRequest = GetObjectRequest.builder()
            .bucket(properties.bucket)
            .key(storageKey)
            .build()
        val presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(Duration.ofSeconds(expirySeconds))
            .getObjectRequest(getObjectRequest)
            .build()
        return s3Presigner.presignGetObject(presignRequest).url().toString()
    }
}
