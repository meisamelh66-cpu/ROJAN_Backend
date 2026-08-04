package ai.rojan.backend.domain.auth

/**
 * Output port for OTP persistence. The real implementation is Redis-backed
 * (natural TTL support, and Redis is already wired into this stack —
 * unconsumed until now) — the domain layer never depends on that directly.
 */
interface OtpRepository {
    /** Replaces any existing OTP for [OneTimePassword.phoneNumber] — at most one active code per phone at a time. */
    fun save(otp: OneTimePassword)

    fun findByPhoneNumber(phoneNumber: PhoneNumber): OneTimePassword?

    fun delete(phoneNumber: PhoneNumber)
}
