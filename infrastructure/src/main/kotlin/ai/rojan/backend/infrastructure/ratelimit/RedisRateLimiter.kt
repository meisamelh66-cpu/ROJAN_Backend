package ai.rojan.backend.infrastructure.ratelimit

import ai.rojan.backend.application.port.RateLimiterPort
import org.springframework.context.annotation.Profile
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Fixed-window counter via Redis `INCR`+`EXPIRE` — simple, not a sliding-
 * window/token-bucket algorithm (a fixed window allows a burst right at the
 * window boundary), an acceptable trade-off for "rate limiting hooks" per
 * the Phase 1 order: a real, working default wired behind [RateLimiterPort],
 * not a fully-tuned production algorithm. Swappable later without touching
 * any caller. Same "not integration-tested against a live Redis here" note
 * as `RedisOtpRepository` applies.
 *
 * Phase 1.1 Auth API Rate Limiting: scoped to `@Profile("!test")` - see
 * [InMemoryRateLimiter]'s own doc comment for why, same split
 * `RealSmsProviderAdapter`/`LoggingSmsProvider` already establish.
 */
@Component
@Profile("!test")
class RedisRateLimiter(
    private val stringRedisTemplate: StringRedisTemplate,
) : RateLimiterPort {

    override fun tryConsume(key: String, limit: Int, window: Duration): Boolean {
        val redisKey = "ratelimit:$key"
        val count = stringRedisTemplate.opsForValue().increment(redisKey) ?: 1L
        if (count == 1L) {
            stringRedisTemplate.expire(redisKey, window)
        }
        return count <= limit
    }
}
