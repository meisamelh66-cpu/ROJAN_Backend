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
        val bytes = MessageDigest.getInstance("SHA-256").digest(code.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
