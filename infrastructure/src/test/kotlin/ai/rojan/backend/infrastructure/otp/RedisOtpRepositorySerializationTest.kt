package ai.rojan.backend.infrastructure.otp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer

/**
 * Regression coverage for the incident where [RedisOtpRepository]'s stored [RedisOtpRepository.OtpRecord]
 * could not be read back — Redis genuinely held the data, but the general-purpose
 * `GenericJackson2JsonRedisSerializer` (built on the shared REST `ObjectMapper`, no
 * polymorphic type metadata) deserialized it into a generic `Map`, and the `as? OtpRecord`
 * cast silently failed, indistinguishable from "key doesn't exist".
 *
 * Tests the exact serializer [RedisOtpRepository] now uses, directly - no live Redis
 * connection needed (`RedisSerializer.serialize`/`deserialize` are pure functions over
 * byte arrays), so this runs deterministically in any environment, matching this codebase's
 * existing preference for self-contained tests (no Docker requirement).
 */
class RedisOtpRepositorySerializationTest {

    private val serializer = Jackson2JsonRedisSerializer(
        RedisOtpRepository.otpRecordObjectMapper(),
        RedisOtpRepository.OtpRecord::class.java,
    )

    @Test
    fun `an OtpRecord written to Redis bytes reads back as an identical OtpRecord`() {
        val original = RedisOtpRepository.OtpRecord(
            codeHash = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            issuedAtEpochMilli = 1_700_000_000_000,
            expiresAtEpochMilli = 1_700_000_120_000,
            attemptsRemaining = 5,
        )

        val bytes = serializer.serialize(original)
        assertNotNull(bytes)

        val roundTripped = serializer.deserialize(bytes)

        assertEquals(original, roundTripped)
    }

    @Test
    fun `round trip preserves the exact codeHash string, not just its presence`() {
        val original = RedisOtpRepository.OtpRecord(
            codeHash = "deadbeef00112233445566778899aabbccddeeff0011223344556677889900",
            issuedAtEpochMilli = 0,
            expiresAtEpochMilli = 120_000,
            attemptsRemaining = 5,
        )

        val roundTripped = serializer.deserialize(serializer.serialize(original))

        assertEquals(original.codeHash, roundTripped?.codeHash)
    }

    @Test
    fun `deserializing produces the exact declared type, not a generic Map`() {
        val original = RedisOtpRepository.OtpRecord("hash", 0, 120_000, 5)

        val roundTripped = serializer.deserialize(serializer.serialize(original))

        // This is the precise assertion that would have caught the original incident: a
        // Map masquerading as the right type would fail this, not just an equals() check.
        assertNotNull(roundTripped as? RedisOtpRepository.OtpRecord)
    }
}
