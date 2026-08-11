package ai.rojan.backend.infrastructure.otp

import ai.rojan.backend.domain.auth.OneTimePassword
import ai.rojan.backend.domain.auth.OtpRepository
import ai.rojan.backend.domain.auth.PhoneNumber
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.springframework.context.annotation.Profile
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant

/**
 * Redis-backed [OtpRepository] — Redis's native TTL support means an
 * expired code is simply gone (no cleanup job needed).
 *
 * Root cause incident (fixed here): this used to depend on the shared,
 * general-purpose `RedisTemplate<String, Any>` from `RedisConfig`, whose
 * value serializer was `GenericJackson2JsonRedisSerializer` built on the
 * app's shared REST `ObjectMapper` (Spring Boot's default — no polymorphic
 * type metadata enabled, deliberately, for security). Writing an [OtpRecord]
 * through it produced plain JSON with no `@class` hint, so on *read* Jackson
 * had no type information to reconstruct — it silently fell back to
 * deserializing a generic `Map` instead, `as? OtpRecord` failed the cast,
 * and `findByPhoneNumber` returned null exactly as if the key never
 * existed. Confirmed live: Redis genuinely held a valid record
 * (`EXISTS`/`TYPE` from `redis-cli` proved it), while this repository's own
 * typed read reported nothing, for every verify attempt.
 *
 * Fix: a dedicated [RedisTemplate] built directly in this class (not
 * shared with [ai.rojan.backend.infrastructure.ratelimit.RedisRateLimiter],
 * which keeps using `RedisConfig`'s general-purpose one — its values are
 * plain counters, not JSON), with a [Jackson2JsonRedisSerializer] bound
 * directly to `OtpRecord::class`. A type-bound serializer needs no
 * polymorphic metadata at all — there is only ever one possible target
 * type — so this is correct without relying on any shared Jackson
 * configuration. Its own dedicated [ObjectMapper] is deliberately separate
 * from the shared REST one.
 *
 * [OtpRecord] stores timestamps as epoch millis, not [Instant] directly —
 * `infrastructure` has no `jackson-datatype-jsr310` on its classpath, and
 * `OtpRecord` is a private-to-this-file wire shape (never exposed, never
 * compared against outside this class), so plain `Long` avoids adding a
 * dependency for a type nothing else needs Jackson to understand.
 *
 * `@Profile("!test")` — see [InMemoryOtpRepository] for the test-profile
 * substitute.
 */
@Repository
@Profile("!test")
class RedisOtpRepository(
    connectionFactory: RedisConnectionFactory,
) : OtpRepository {

    private val redisTemplate: RedisTemplate<String, OtpRecord> = RedisTemplate<String, OtpRecord>().apply {
        setConnectionFactory(connectionFactory)
        keySerializer = StringRedisSerializer()
        valueSerializer = Jackson2JsonRedisSerializer(otpRecordObjectMapper(), OtpRecord::class.java)
        afterPropertiesSet()
    }

    override fun save(otp: OneTimePassword) {
        val ttl = Duration.between(Instant.now(), otp.expiresAt).let { if (it.isNegative) Duration.ZERO else it }
        redisTemplate.opsForValue().set(
            keyFor(otp.phoneNumber),
            OtpRecord(otp.codeHash, otp.issuedAt.toEpochMilli(), otp.expiresAt.toEpochMilli(), otp.attemptsRemaining),
            ttl,
        )
    }

    override fun findByPhoneNumber(phoneNumber: PhoneNumber): OneTimePassword? {
        val record = redisTemplate.opsForValue().get(keyFor(phoneNumber)) ?: return null
        return OneTimePassword.reconstitute(
            phoneNumber,
            record.codeHash,
            Instant.ofEpochMilli(record.issuedAtEpochMilli),
            Instant.ofEpochMilli(record.expiresAtEpochMilli),
            record.attemptsRemaining,
        )
    }

    override fun delete(phoneNumber: PhoneNumber) {
        redisTemplate.delete(keyFor(phoneNumber))
    }

    private fun keyFor(phoneNumber: PhoneNumber) = "otp:code:${phoneNumber.value}"

    internal data class OtpRecord(
        val codeHash: String,
        val issuedAtEpochMilli: Long,
        val expiresAtEpochMilli: Long,
        val attemptsRemaining: Int,
    )

    companion object {
        /** Dedicated to OTP Redis (de)serialization only — deliberately not the shared REST `ObjectMapper` (see class doc). */
        internal fun otpRecordObjectMapper(): ObjectMapper = ObjectMapper().registerKotlinModule()
    }
}
