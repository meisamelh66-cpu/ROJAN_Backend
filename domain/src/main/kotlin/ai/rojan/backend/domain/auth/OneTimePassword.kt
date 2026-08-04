package ai.rojan.backend.domain.auth

import java.time.Instant

/**
 * A single issued OTP for [phoneNumber]. Framework-free — [codeHash] is an
 * opaque string (hashing algorithm is an infrastructure/application concern,
 * mirroring how [ai.rojan.backend.domain.user.User.passwordHash] is also
 * just an opaque string here). At most one active [OneTimePassword] exists
 * per phone number at a time — issuing a new one (via request or resend)
 * replaces any previous one, enforced by the repository, not this class.
 */
class OneTimePassword private constructor(
    val phoneNumber: PhoneNumber,
    val codeHash: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
    attemptsRemaining: Int,
) {
    var attemptsRemaining: Int = attemptsRemaining
        private set

    fun isExpired(now: Instant): Boolean = now >= expiresAt

    /** True once every verify attempt has been used up — the code must be treated as dead even if not yet expired. */
    val isExhausted: Boolean get() = attemptsRemaining <= 0

    /** Returns a new instance with one fewer attempt remaining — this class is immutable, callers persist the result. */
    fun withFailedAttempt(): OneTimePassword =
        OneTimePassword(phoneNumber, codeHash, issuedAt, expiresAt, (attemptsRemaining - 1).coerceAtLeast(0))

    companion object {
        fun issue(phoneNumber: PhoneNumber, codeHash: String, now: Instant, ttlSeconds: Long, maxAttempts: Int): OneTimePassword {
            require(maxAttempts > 0) { "maxAttempts must be positive" }
            return OneTimePassword(phoneNumber, codeHash, now, now.plusSeconds(ttlSeconds), maxAttempts)
        }

        fun reconstitute(
            phoneNumber: PhoneNumber,
            codeHash: String,
            issuedAt: Instant,
            expiresAt: Instant,
            attemptsRemaining: Int,
        ): OneTimePassword = OneTimePassword(phoneNumber, codeHash, issuedAt, expiresAt, attemptsRemaining)
    }
}
