package ai.rojan.backend.infrastructure.security

import ai.rojan.backend.application.port.RefreshTokenStorePort
import org.springframework.context.annotation.Profile
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * One `SET key value EX ttl` per family, keyed by family id, holding the currently-active jti.
 * Redis' own TTL is the entire lifecycle mechanism - no cleanup job, nothing to age out
 * separately from the refresh token's own claimed expiry (the caller always passes the same
 * [jwtProperties.refreshTokenTtlDays]-derived duration `JwtTokenProvider` used to sign the token,
 * so the two can never drift apart).
 *
 * Same `@Profile("!test")`/[InMemoryRefreshTokenStore] split `RedisRateLimiter`/`InMemoryRateLimiter`
 * already establish, for the same reason: the full-Spring-context `bootstrap` integration suite
 * needs a dependency-free stand-in to boot without a real Redis.
 */
@Component
@Profile("!test")
class RedisRefreshTokenStore(
    private val stringRedisTemplate: StringRedisTemplate,
) : RefreshTokenStorePort {

    override fun activate(familyId: String, jti: String, ttl: Duration) {
        stringRedisTemplate.opsForValue().set(keyFor(familyId), jti, ttl)
    }

    override fun currentJti(familyId: String): String? =
        stringRedisTemplate.opsForValue().get(keyFor(familyId))

    override fun revokeFamily(familyId: String) {
        stringRedisTemplate.delete(keyFor(familyId))
    }

    private fun keyFor(familyId: String) = "refresh:family:$familyId"
}
