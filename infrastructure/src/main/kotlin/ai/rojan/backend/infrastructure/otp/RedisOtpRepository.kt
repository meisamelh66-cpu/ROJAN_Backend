package ai.rojan.backend.infrastructure.otp

import ai.rojan.backend.domain.auth.OneTimePassword
import ai.rojan.backend.domain.auth.OtpRepository
import ai.rojan.backend.domain.auth.PhoneNumber
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant

/**
 * Redis-backed [OtpRepository] — Redis's native TTL support means an
 * expired code is simply gone (no cleanup job needed), and this is the
 * first real consumer of the `RedisTemplate` bean this stack has wired
 * since its initial scope. Not integration-tested against a live Redis in
 * this pass — none exists in the test environment (confirmed by direct
 * inspection before this phase started); covered instead by an in-memory
 * fake at the application-layer use-case tests, matching this codebase's
 * own established testing convention for every other repository port.
 */
@Repository
class RedisOtpRepository(
    private val redisTemplate: RedisTemplate<String, Any>,
) : OtpRepository {

    override fun save(otp: OneTimePassword) {
        val ttl = Duration.between(Instant.now(), otp.expiresAt).let { if (it.isNegative) Duration.ZERO else it }
        redisTemplate.opsForValue().set(
            keyFor(otp.phoneNumber),
            OtpRecord(otp.codeHash, otp.issuedAt, otp.expiresAt, otp.attemptsRemaining),
            ttl,
        )
    }

    override fun findByPhoneNumber(phoneNumber: PhoneNumber): OneTimePassword? {
        val record = redisTemplate.opsForValue().get(keyFor(phoneNumber)) as? OtpRecord ?: return null
        return OneTimePassword.reconstitute(phoneNumber, record.codeHash, record.issuedAt, record.expiresAt, record.attemptsRemaining)
    }

    override fun delete(phoneNumber: PhoneNumber) {
        redisTemplate.delete(keyFor(phoneNumber))
    }

    private fun keyFor(phoneNumber: PhoneNumber) = "otp:code:${phoneNumber.value}"

    private data class OtpRecord(val codeHash: String, val issuedAt: Instant, val expiresAt: Instant, val attemptsRemaining: Int)
}
