package ai.rojan.backend.infrastructure.ratelimit

import ai.rojan.backend.application.port.RateLimiterPort
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Test-only placeholder — an in-memory fixed-window counter, functionally
 * equivalent to [RedisRateLimiter] but with no external Redis dependency.
 * Phase 1.1 Auth API Rate Limiting: [RateLimiterPort] now sits on the call
 * path of `/auth/login`, `/auth/register`, `/auth/refresh` - endpoints the
 * full-Spring-context `bootstrap` integration suite exercises heavily as
 * setup for its own, unrelated domain flow tests - so without this, that
 * whole suite would need a real Redis just to boot far enough to run any
 * of them. Same "test needs a dependency-free stand-in for an external
 * adapter" reasoning as `LoggingSmsProvider`'s own doc comment, and the
 * same `@Profile("test")`/`@Profile("!test")` split
 * [RedisRateLimiter]/`RealSmsProviderAdapter` already establish.
 *
 * [ConcurrentHashMap.compute] makes the read-increment-write atomic per
 * key, matching Redis' own `INCR` atomicity - correctness under concurrent
 * access matters here specifically because
 * `BookingConflictConcurrencyIntegrationTest` fires genuinely concurrent
 * requests through this same Spring context. Single-JVM only, not a
 * substitute for [RedisRateLimiter]'s real, shared-across-instances
 * counter in production.
 */
@Component
@Profile("test")
class InMemoryRateLimiter : RateLimiterPort {

    private data class Window(val count: Int, val resetAt: Instant)

    private val windows = ConcurrentHashMap<String, Window>()

    override fun tryConsume(key: String, limit: Int, window: Duration): Boolean {
        val now = Instant.now()
        var allowed = false

        windows.compute(key) { _, existing ->
            val current = if (existing == null || now.isAfter(existing.resetAt)) {
                Window(count = 0, resetAt = now.plus(window))
            } else {
                existing
            }
            val updated = current.copy(count = current.count + 1)
            allowed = updated.count <= limit
            updated
        }

        return allowed
    }
}
