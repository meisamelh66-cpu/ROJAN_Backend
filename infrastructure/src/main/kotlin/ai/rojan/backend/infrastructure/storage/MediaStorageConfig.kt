package ai.rojan.backend.infrastructure.storage

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import java.net.URI

/**
 * Wires the S3-compatible [S3Client] bean that [S3CompatibleMediaStorage]
 * (itself `@Component`-registered) constructor-injects.
 * [MediaStorageProperties] has safe MinIO-local defaults (unlike
 * `SmsPropertiesConfig`), so this needs no test-profile exclusion -
 * constructing an [S3Client] makes no network call by itself, only actual
 * upload/delete/resolveUrl calls do.
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
}
