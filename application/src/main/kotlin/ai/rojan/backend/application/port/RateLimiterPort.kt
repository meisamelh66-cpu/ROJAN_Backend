package ai.rojan.backend.application.port

import java.time.Duration

/**
 * Output port for rate limiting — generic (a bare key/limit/window), not
 * OTP-specific, so the same mechanism serves every per-phone and per-IP
 * check this phase needs without inventing a new abstraction per call site.
 * "Rate limiting hooks" per the Phase 1 order: this is the hook: a real,
 * working default implementation (Redis-backed, fixed-window counter) is
 * wired in, but any check's [key]/[limit]/[window] can be tuned or the
 * whole implementation swapped without touching a caller.
 */
interface RateLimiterPort {
    /** Records one attempt under [key] and returns `true` if it's within [limit] for the trailing [window]; `false` if [key] has already hit [limit] within [window]. */
    fun tryConsume(key: String, limit: Int, window: Duration): Boolean
}
