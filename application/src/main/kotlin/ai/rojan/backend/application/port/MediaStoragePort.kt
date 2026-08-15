package ai.rojan.backend.application.port

/**
 * Output port for external media file storage — kept out of this
 * (framework-free) module's dependency graph exactly like
 * [QrCodeGeneratorPort]/[SmsProviderPort]. The database only ever sees a
 * [storageKey] string via [ai.rojan.backend.domain.media.MediaAsset] — the
 * real bytes live wherever this port's implementation puts them (local disk
 * today, swappable for S3/object storage later without touching
 * application or domain code).
 */
interface MediaStoragePort {
    /** Writes [content] under [storageKey], returning the publicly reachable URL for it. */
    fun store(storageKey: String, content: ByteArray, mimeType: String): String

    fun delete(storageKey: String)
}
