package ai.rojan.backend.infrastructure.persistence.idempotency

import ai.rojan.backend.application.port.IdempotencyLookup
import ai.rojan.backend.application.port.IdempotencyPort
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val TTL: Duration = Duration.ofHours(24)

/**
 * Persists idempotency-key -> response mappings in Postgres. A stored
 * request's fingerprint is compared on lookup so a key reused with a
 * different payload is reported as [IdempotencyLookup.Conflict] rather than
 * silently replaying the wrong response; an expired record is treated as
 * absent so the caller can proceed and overwrite it.
 */
@Component
class IdempotencyAdapter(
    private val repository: IdempotencyKeySpringDataRepository,
    private val objectMapper: ObjectMapper,
) : IdempotencyPort {

    override fun <T : Any> lookup(key: String, requestFingerprint: String, responseType: Class<T>): IdempotencyLookup<T> {
        val existing = repository.findByIdempotencyKey(key) ?: return IdempotencyLookup.NotFound
        if (existing.expiresAt.isBefore(Instant.now())) return IdempotencyLookup.NotFound
        if (existing.requestHash != requestFingerprint) return IdempotencyLookup.Conflict

        val body = objectMapper.readValue(existing.responseBody, responseType)
        return IdempotencyLookup.Replay(existing.responseStatus, body)
    }

    @Transactional
    override fun store(key: String, requestFingerprint: String, statusCode: Int, response: Any) {
        val now = Instant.now()
        val entity = IdempotencyKeyJpaEntity(
            id = UUID.randomUUID(),
            idempotencyKey = key,
            requestHash = requestFingerprint,
            responseStatus = statusCode,
            responseBody = objectMapper.writeValueAsString(response),
            createdAt = now,
            expiresAt = now.plus(TTL),
        )
        repository.save(entity)
    }
}
