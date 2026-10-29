package ai.rojan.backend.infrastructure.security

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * ROJAN_BACKEND_SECURITY_AUDIT_v1.md F5 / GHSA-mg83-c7gq-rv5c.
 *
 * Empirically confirmed against the real, resolved encoder (Spring Security
 * 6.3.10, after this module's root build.gradle.kts dependencyManagement
 * override): `BCryptPasswordEncoder.encode()` now throws
 * `IllegalArgumentException("password cannot be more than 72 bytes")` for
 * any input whose **UTF-8-encoded** length exceeds 72 bytes - not 72
 * characters. A Persian/Farsi password (ROJAN is an RTL-Persian-first
 * platform) packs 2 UTF-8 bytes per character, so a 72-*character* Farsi
 * string is already 144 bytes - double the real limit - despite satisfying
 * any character-count-based check. This is the fact `@Utf8ByteSize`
 * (api/.../common/Utf8ByteSize.kt) validates against directly, rather than
 * relying on `@Size`'s character count or on the encoder's own exception
 * surfacing as a generic 400.
 */
class BCryptPasswordEncoderAdapterTest {

    private val encoder = BCryptPasswordEncoderAdapter()

    @Test
    fun `an ASCII password exactly at the 72-byte boundary is accepted and verifies correctly`() {
        val password = "a".repeat(72)
        val hash = encoder.encode(password)

        assertTrue(encoder.matches(password, hash))
    }

    @Test
    fun `an ASCII password one byte past the boundary is rejected by encode() itself`() {
        val tooLong = "a".repeat(73)

        val ex = assertThrows<IllegalArgumentException> { encoder.encode(tooLong) }
        assertTrue(ex.message?.contains("72 bytes") == true, "expected a 72-bytes message, got: ${ex.message}")
    }

    @Test
    fun `a 72-character Farsi password - 144 UTF-8 bytes - is rejected by encode() itself, not silently truncated`() {
        // "سلام" = 4 Persian characters = 8 UTF-8 bytes (2 bytes/char).
        // 18 repeats = 72 characters, 144 bytes - exactly double the real
        // 72-byte limit despite matching the naive 72-character count.
        val farsiPassword = "سلام".repeat(18)
        check(farsiPassword.length == 72)
        check(farsiPassword.toByteArray(Charsets.UTF_8).size == 144)

        val ex = assertThrows<IllegalArgumentException> { encoder.encode(farsiPassword) }
        assertTrue(ex.message?.contains("72 bytes") == true, "expected a 72-bytes message, got: ${ex.message}")
    }

    @Test
    fun `a Farsi password within the real 72-byte boundary is accepted and verifies correctly, distinct from a different password of the same byte length`() {
        // 36 Persian characters * 2 bytes = exactly 72 bytes.
        val password = "سلام".repeat(9)
        check(password.toByteArray(Charsets.UTF_8).size == 72)
        val differentPassword = "درود".repeat(9)
        check(differentPassword.toByteArray(Charsets.UTF_8).size == 72)

        val hash = encoder.encode(password)

        assertTrue(encoder.matches(password, hash))
        assertFalse(encoder.matches(differentPassword, hash))
    }

    @Test
    fun `two in-bounds passwords sharing a long common prefix but differing in their tail never cross-match`() {
        val prefix = "a".repeat(60)
        val passwordA = prefix + "AAAAAAAAAAAA"
        val passwordB = prefix + "BBBBBBBBBBBB"
        check(passwordA.length == 72 && passwordB.length == 72)

        val hashOfA = encoder.encode(passwordA)

        assertTrue(encoder.matches(passwordA, hashOfA))
        assertFalse(encoder.matches(passwordB, hashOfA))
    }

    @Test
    fun `an incorrect password never matches, regardless of length`() {
        val hash = encoder.encode("correct-password-123")

        assertFalse(encoder.matches("wrong-password-456", hash))
    }
}
