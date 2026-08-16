package ai.rojan.backend.application.port

/**
 * Output port for file storage - kept out of this (framework-free)
 * module's dependency graph exactly like [QrCodeGeneratorPort]/
 * [SmsProviderPort] already are; the real S3-compatible client lives only
 * in the infrastructure implementation. Never a public/private access
 * decision here - Phase 1 (Media Foundation) only ever stores public
 * assets (logo/cover/gallery/portfolio); a future document-storage phase
 * that needs private, signed-access objects extends this port rather than
 * bolting visibility logic onto [resolveUrl].
 */
interface MediaStoragePort {
    /** Uploads [content] under [storageKey], overwriting if the key already exists. */
    fun upload(storageKey: String, content: ByteArray, contentType: String)

    /** Deletes the object at [storageKey]. A missing object is not an error - deletion is idempotent from the caller's perspective. */
    fun delete(storageKey: String)

    /** Resolves [storageKey] to a publicly servable URL. */
    fun resolveUrl(storageKey: String): String
}
