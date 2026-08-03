package ai.rojan.backend.application.port

/**
 * Output port for HTTP-level idempotency-key handling (RFC-draft
 * "Idempotency-Key" pattern). Deliberately framework-free: the adapter in
 * infrastructure owns (de)serialization and storage, so `application` and
 * `api` only deal with an already-computed request fingerprint and a typed
 * response body.
 */
interface IdempotencyPort {
    /**
     * [key] is the client-supplied Idempotency-Key header value. [requestFingerprint]
     * is a caller-computed hash of the semantically-relevant request fields,
     * used to detect the same key being replayed with a different payload.
     */
    fun <T : Any> lookup(key: String, requestFingerprint: String, responseType: Class<T>): IdempotencyLookup<T>

    fun store(key: String, requestFingerprint: String, statusCode: Int, response: Any)
}

sealed interface IdempotencyLookup<out T> {
    data class Replay<T>(val statusCode: Int, val body: T) : IdempotencyLookup<T>
    data object Conflict : IdempotencyLookup<Nothing>
    data object NotFound : IdempotencyLookup<Nothing>
}
