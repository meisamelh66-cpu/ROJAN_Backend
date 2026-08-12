package ai.rojan.backend.application.port

/**
 * Output port for QR code image generation — kept out of this
 * (framework-free) module's dependency graph exactly like
 * [RateLimiterPort]/[IdempotencyPort]/[TokenProviderPort] already are; the
 * real encoding library lives only in the infrastructure implementation.
 */
interface QrCodeGeneratorPort {
    /** Encodes [content] (a URL) as a square PNG, [sizePx] pixels on each side. */
    fun generatePng(content: String, sizePx: Int): ByteArray
}
