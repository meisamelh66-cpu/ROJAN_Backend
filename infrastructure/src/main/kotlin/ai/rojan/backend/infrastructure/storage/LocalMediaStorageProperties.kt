package ai.rojan.backend.infrastructure.storage

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Local-disk media storage config. Deliberately separate from
 * [MediaStorageProperties] (S3-shaped) rather than folded into it - the
 * two providers share no fields (a bucket/region/access-key trio means
 * nothing to a filesystem path), and keeping them apart means adding a
 * future third provider never means touching either. [storageRoot] must
 * match wherever the deployment mounts persistent storage (`/app/uploads`
 * inside the container, bind-mounted from the host - see
 * `docker-compose.prod.yml`'s `uploads` volume, already wired for the
 * `/media/` Nginx static location this adapter's URLs point at).
 */
@ConfigurationProperties(prefix = "rojan.media")
data class LocalMediaStorageProperties(
    val storageRoot: String = "/app/uploads",
    /** Base origin [LocalDiskMediaStorageAdapter.resolveUrl] builds `/media/{storageKey}` links from - the public-facing host in front of Nginx's static location, not this JVM's own address. */
    val publicBaseUrl: String = "http://localhost:8080",
)
