package ai.rojan.backend.infrastructure.media

import ai.rojan.backend.application.port.MediaStoragePort
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * [MediaStoragePort] implementation backing directly onto disk, served back
 * out by Nginx as a static `/media/` location (see
 * `docker/nginx/conf.d/rojan.conf`) rather than proxied through this JVM —
 * the same reasoning `docker-compose.prod.yml` already documented for
 * `/opt/rojan/uploads` before this phase wired it up. Swappable for an
 * S3/object-storage adapter later without any application/domain change —
 * this is the only class that knows files live on a local filesystem at
 * all.
 */
@Component
class LocalDiskMediaStorageAdapter(
    private val mediaProperties: MediaProperties,
    @Value("\${rojan.public.base-url:https://app.rojan.ai}") private val publicBaseUrl: String,
) : MediaStoragePort {

    private val root: Path = Path.of(mediaProperties.storageRoot).toAbsolutePath().normalize()

    override fun store(storageKey: String, content: ByteArray, mimeType: String): String {
        val target = resolveWithinRoot(storageKey)
        Files.createDirectories(target.parent)
        Files.write(target, content)
        return "${publicBaseUrl.trimEnd('/')}/media/$storageKey"
    }

    override fun delete(storageKey: String) {
        Files.deleteIfExists(resolveWithinRoot(storageKey))
    }

    /** Rejects any [storageKey] that would resolve outside [root] (`..` segments, absolute paths) - defense in depth on top of [storageKey] always being backend-generated (`UploadMediaUseCase`), never client-supplied. */
    private fun resolveWithinRoot(storageKey: String): Path {
        val resolved = root.resolve(storageKey).normalize()
        require(resolved.startsWith(root)) { "Invalid media storage key: $storageKey" }
        return resolved
    }
}
