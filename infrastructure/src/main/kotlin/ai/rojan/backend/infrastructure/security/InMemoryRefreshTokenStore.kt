package ai.rojan.backend.infrastructure.security

import ai.rojan.backend.application.port.RefreshTokenStorePort
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Test-only placeholder — functionally equivalent to [RedisRefreshTokenStore] but with no
 * external Redis dependency, same reasoning as [InMemoryRateLimiter]'s own doc comment: the
 * full-Spring-context `bootstrap` integration suite needs this to boot and exercise the real
 * `AuthenticationFlowIntegrationTest`/`OtpAuthenticationFlowIntegrationTest` flows without a real
 * Redis. [ConcurrentHashMap] makes activate/read/revoke safe under concurrent access, matching
 * Redis' own atomicity for a single key.
 */
@Component
@Profile("test")
class InMemoryRefreshTokenStore : RefreshTokenStorePort {

    private data class Entry(val jti: String, val expiresAt: Instant)

    private val families = ConcurrentHashMap<String, Entry>()

    override fun activate(familyId: String, jti: String, ttl: Duration) {
        families[familyId] = Entry(jti, Instant.now().plus(ttl))
    }

    override fun currentJti(familyId: String): String? {
        val entry = families[familyId] ?: return null
        if (Instant.now().isAfter(entry.expiresAt)) {
            families.remove(familyId)
            return null
        }
        return entry.jti
    }

    override fun revokeFamily(familyId: String) {
        families.remove(familyId)
    }
}
