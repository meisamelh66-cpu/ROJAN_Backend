package ai.rojan.backend.api.common

/**
 * Thrown when a client replays an `Idempotency-Key` with a request body that
 * doesn't match the one originally associated with that key. This is an
 * HTTP-delivery concern, not a domain rule, so it lives in the API layer
 * rather than as a [ai.rojan.backend.domain.common.DomainException].
 */
class IdempotencyKeyConflictException(key: String) :
    RuntimeException("Idempotency-Key '$key' was already used with a different request body")
