package ai.rojan.backend.application.booking

import ai.rojan.backend.application.port.RateLimiterPort
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * D11: a real counting, fixed-window [RateLimiterPort] test double - unlike
 * [ai.rojan.backend.application.auth.RecordingRateLimiter] (which only allows/denies by a static
 * key-prefix set, used everywhere else in this codebase to test *that the right keys get
 * consumed*), this one actually counts per key and expires per window, so the Nth-request/window-
 * reset behavior D11's own threshold decision needs to be verified against can be asserted
 * directly. Same algorithm as [ai.rojan.backend.infrastructure.ratelimit.InMemoryRateLimiter]
 * (the real `@Profile("test")` bean wired into the full Spring context) - reimplemented here only
 * because `application` has no dependency on `infrastructure` to reuse that class directly.
 * [advanceTimeBy] gives tests a controllable clock instead of a real `Thread.sleep` for the
 * window-reset scenario.
 */
internal class CountingRateLimiterTestFixture : RateLimiterPort {

    private data class Window(val count: Int, val resetAt: Instant)

    private val windows = ConcurrentHashMap<String, Window>()
    private var now: Instant = Instant.now()

    fun advanceTimeBy(duration: Duration) {
        now = now.plus(duration)
    }

    override fun tryConsume(key: String, limit: Int, window: Duration): Boolean {
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
