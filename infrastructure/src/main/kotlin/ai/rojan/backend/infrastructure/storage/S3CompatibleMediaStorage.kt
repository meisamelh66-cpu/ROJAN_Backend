package ai.rojan.backend.infrastructure.storage

import ai.rojan.backend.application.port.MediaStoragePort
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
 * The real [MediaStoragePort] implementation - speaks the S3 API via the
 * AWS SDK, pointed at a MinIO endpoint today ([MediaStorageProperties]).
 * Nothing here is MinIO-specific: swapping to real AWS S3 or another
 * S3-compatible provider later is a config change ([MediaStorageProperties.endpoint]),
 * never a code change - the whole point of going through [MediaStoragePort]
 * rather than a MinIO-only client. `@Component`-registered directly,
 * matching [ai.rojan.backend.infrastructure.sms.RealSmsProviderAdapter]'s
 * convention for a port's single real implementation - and, like that
 * class, excluded from the `test` profile in favor of [InMemoryMediaStorage]
 * (this environment has no MinIO/Docker available for a real integration
 * test to hit).
 */
@Component
@Profile("!test")
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
