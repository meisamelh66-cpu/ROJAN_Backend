package ai.rojan.backend.domain.auth

import java.time.Instant

/**
 * A narrowly-scoped, time-boxed, single-phone fixed-code override that lets
 * ONE named account log in during a defined window — built specifically for
 * app-store (Cafe Bazaar) reviewer access, where an async human reviewer
 * cannot receive/relay a live SMS OTP.
 *
 * Deliberately isolated from [OneTimePassword]/[OtpRepository] — this entity
 * and its use cases never read, write, or otherwise touch the real per-phone
 * OTP flow for this (or any) phone number. [codeHash] is produced the same
 * way a real OTP code's hash is (see `OtpHashing` in the application layer) —
 * the plaintext code is never stored.
 *
 * Keyed one-per-phone (the real schema's `phone_number` primary key) — there
 * is structurally never more than one live grant for a given phone.
 */
data class ReviewerAccessGrant(
    val phoneNumber: PhoneNumber,
    val codeHash: String,
    val activatedAt: Instant,
    val expiresAt: Instant,
    val active: Boolean,
) {
    /** True only while [active] and strictly before [expiresAt] — evaluated fresh against real server time on every check, never cached or client-trusted. */
    fun isUsableAt(now: Instant): Boolean = active && now.isBefore(expiresAt)
}
