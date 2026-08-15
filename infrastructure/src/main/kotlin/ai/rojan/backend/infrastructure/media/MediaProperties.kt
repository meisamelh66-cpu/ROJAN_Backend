package ai.rojan.backend.infrastructure.media

import org.springframework.boot.context.properties.ConfigurationProperties

/** Salon Identity Foundation Phase A. [storageRoot] must match wherever the deployment actually mounts persistent storage (`/app/uploads` inside the container, bind-mounted from `${ROJAN_DATA_ROOT:-/opt/rojan}/uploads` - see `docker-compose.prod.yml`). */
@ConfigurationProperties(prefix = "rojan.media")
data class MediaProperties(
    val storageRoot: String = "/app/uploads",
    val maxFileSizeBytes: Long = 5_242_880L,
    val allowedMimeTypes: List<String> = listOf("image/jpeg", "image/png", "image/webp"),
)
