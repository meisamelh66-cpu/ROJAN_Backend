package ai.rojan.backend.infrastructure.storage

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI

/**
 * Wires the S3-compatible [S3Client]/[S3Presigner] beans that
 * [S3CompatibleMediaStorage] (itself `@Component`-registered)
 * constructor-injects. [MediaStorageProperties] has safe MinIO-local
 * defaults (unlike `SmsPropertiesConfig`), so this needs no test-profile
 * exclusion - constructing either client makes no network call by
 * itself, only actual upload/delete/resolveUrl/resolveSignedUrl calls do.
 */
@Configuration
@EnableConfigurationProperties(MediaStorageProperties::class)
class MediaStorageConfig {

    @Bean
    fun s3Client(properties: MediaStorageProperties): S3Client =
        S3Client.builder()
            .endpointOverride(URI.create(properties.endpoint))
            .region(Region.of(properties.region))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.accessKey, properties.secretKey),
                ),
            )
            // MinIO (and most non-AWS S3-compatible providers) require
            // path-style bucket addressing (host/bucket/key), not AWS's
            // default virtual-hosted-style (bucket.host/key).
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()

    /** Document Archive (Phase 2) - powers [S3CompatibleMediaStorage.resolveSignedUrl], the only way private document content is ever served. */
    @Bean
    fun s3Presigner(properties: MediaStorageProperties): S3Presigner =
        S3Presigner.builder()
            .endpointOverride(URI.create(properties.endpoint))
            .region(Region.of(properties.region))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.accessKey, properties.secretKey),
                ),
            )
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()
}
