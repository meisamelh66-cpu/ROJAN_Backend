package ai.rojan.backend.application.auth

import java.security.MessageDigest

/**
 * SHA-256, not [ai.rojan.backend.application.port.PasswordEncoderPort]'s BCrypt -
 * a deliberately lighter choice: unlike a password, an OTP code is single-use,
 * short-lived (see `OtpPolicy.ttlSeconds`), and already attempt-/rate-limited,
 * so BCrypt's intentional slowness buys little extra safety here at the cost
 * of real latency on every `/otp/verify` call. No salt: no two valid codes
 * for the same phone ever coexist (`OtpRepository.save` replaces the prior
 * one), so there is nothing a precomputed table gains beyond what rate
 * limiting already blocks.
 */
internal object OtpHashing {
    fun hash(code: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(normalizeDigits(code).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Persian (۰-۹, U+06F0-U+06F9) and Arabic-Indic (٠-٩, U+0660-U+0669) digits - what a
     * Farsi/Arabic keyboard produces for numeric input - normalize to ASCII before hashing.
     * Without this, a user who types the visually-correct code via such a keyboard never
     * hash-matches the ASCII code [RequestOtpUseCase] actually generated and sent. A no-op
     * for the ASCII digits `SecureRandom`-generated codes are already made of, so this is
     * safe on the generation side too - both call sites share this one function.
     */
    private fun normalizeDigits(input: String): String = input.map { ch ->
        when (ch.code) {
            in 0x06F0..0x06F9 -> '0' + (ch.code - 0x06F0)
            in 0x0660..0x0669 -> '0' + (ch.code - 0x0660)
            else -> ch
        }
    }.joinToString("")
}
