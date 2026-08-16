package ai.rojan.backend.infrastructure.storage

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * S3-compatible object storage config. Unlike `SmsProperties`/`JwtProperties`,
 * this has safe localhost defaults (a local MinIO instance's own default
 * credentials) rather than failing loudly when unset - an unconfigured dev
 * environment pointing at a local MinIO container is the normal case, not
 * a missing-secret footgun like an unset JWT signing key would be.
 */
@ConfigurationProperties(prefix = "rojan.storage")
data class MediaStorageProperties(
    val endpoint: String = "http://localhost:9000",
    val region: String = "us-east-1",
    val bucket: String = "rojan-media",
    val accessKey: String = "minioadmin",
    val secretKey: String = "minioadmin",
    /** Public-facing base URL [S3CompatibleMediaStorage.resolveUrl] builds servable links from - may differ from [endpoint] (e.g. a CDN/reverse-proxy in front of the bucket). */
    val publicBaseUrl: String = "http://localhost:9000/rojan-media",
)
