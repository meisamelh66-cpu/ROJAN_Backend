package ai.rojan.backend.application.port

/**
 * Output port for file storage - kept out of this (framework-free)
 * module's dependency graph exactly like [QrCodeGeneratorPort]/
 * [SmsProviderPort] already are; the real S3-compatible client lives only
 * in the infrastructure implementation. Phase 1 (Media Foundation) only
 * ever stored public assets (logo/cover/gallery/portfolio) via
 * [resolveUrl]; Phase 2 (Document Archive) adds [resolveSignedUrl] for
 * private, time-limited access - additive, [resolveUrl] is unchanged in
 * shape and still the only path for public media.
 */
interface MediaStoragePort {
    /** Uploads [content] under [storageKey], overwriting if the key already exists. */
    fun upload(storageKey: String, content: ByteArray, contentType: String)

    /** Deletes the object at [storageKey]. A missing object is not an error - deletion is idempotent from the caller's perspective. */
    fun delete(storageKey: String)

    /** Resolves [storageKey] to a publicly servable URL. Public media only - never call this for a DOCUMENT-typed asset. */
    fun resolveUrl(storageKey: String): String

    /** A time-limited, GET-only signed URL for [storageKey], valid for [expirySeconds] - the only way private (document) content is ever served. Minted fresh on every call, never cached by the caller. */
    fun resolveSignedUrl(storageKey: String, expirySeconds: Long): String
}
