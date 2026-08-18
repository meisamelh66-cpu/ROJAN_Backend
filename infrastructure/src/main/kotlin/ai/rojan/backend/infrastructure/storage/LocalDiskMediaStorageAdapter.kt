package ai.rojan.backend.infrastructure.storage

import ai.rojan.backend.application.port.MediaStoragePort
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * [MediaStoragePort] implementation backing directly onto disk, served
 * back out by Nginx as a static `/media/` location rather than proxied
 * through this JVM (see `docker-compose.prod.yml`'s `uploads` volume and
 * `docker/nginx/conf.d/rojan.conf`'s `/media/` block - both already live
 * in production, ported from the Salon Identity Foundation phase
 * unchanged). The default [MediaStoragePort] bean for every non-`test`
 * profile - mirrors [ai.rojan.backend.infrastructure.sms.RealSmsProviderAdapter]'s
 * role as the plain, no-`@ConditionalOnProperty` default that
 * [S3CompatibleMediaStorage] (its own doc comment) steps in front of via
 * `@Primary` only when `rojan.storage.provider=s3` is explicitly set - no
 * annotation here needed for that tie-break to work, per Spring's own
 * `@Primary` semantics. Reasoning for local-disk-as-default over S3, not
 * MinIO/S3: production has no S3-compatible object store deployed today,
 * and this adapter reuses infrastructure the current production
 * deployment already has running, rather than introducing a new stateful
 * service alongside an unrelated schema reconciliation.
 */
@Component
@Profile("!test")
class LocalDiskMediaStorageAdapter(
    private val properties: LocalMediaStorageProperties,
) : MediaStoragePort {

    private val root: Path = Path.of(properties.storageRoot).toAbsolutePath().normalize()

    override fun upload(storageKey: String, content: ByteArray, contentType: String) {
        val target = resolveWithinRoot(storageKey)
        Files.createDirectories(target.parent)
        Files.write(target, content)
    }

    override fun delete(storageKey: String) {
        Files.deleteIfExists(resolveWithinRoot(storageKey))
    }

    override fun resolveUrl(storageKey: String): String =
        "${properties.publicBaseUrl.trimEnd('/')}/media/$storageKey"

    /**
     * Local disk has no real signing mechanism - Nginx's `/media/` location
     * serves every file under it as a plain, permanently-public static
     * asset, with no token/expiry concept at all. Returns the same URL
     * [resolveUrl] would, ignoring [expirySeconds] entirely - a disclosed
     * limitation, not a bug: this provider is only ever selected as the
     * default for public salon-identity media (logo/cover/gallery/portfolio,
     * Phase 1's own scope), never for `DOCUMENT`-typed assets - serving a
     * genuinely private, time-limited document over this adapter would
     * need real work (a token-checking endpoint of its own) this phase
     * does not do. [S3CompatibleMediaStorage.resolveSignedUrl] remains the
     * only implementation with real signing semantics.
     */
    override fun resolveSignedUrl(storageKey: String, expirySeconds: Long): String = resolveUrl(storageKey)

    /** Rejects any [storageKey] that would resolve outside [root] (`..` segments, absolute paths) - defense in depth on top of [storageKey] always being backend-generated ([ai.rojan.backend.application.media.UploadMediaUseCase]), never client-supplied. */
    private fun resolveWithinRoot(storageKey: String): Path {
        val resolved = root.resolve(storageKey).normalize()
        require(resolved.startsWith(root)) { "Invalid media storage key: $storageKey" }
        return resolved
    }
}
