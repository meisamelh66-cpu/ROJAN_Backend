package ai.rojan.backend.domain.user

import ai.rojan.backend.domain.auth.PhoneNumber
import java.time.Instant
import java.util.UUID

enum class UserRole {
    CUSTOMER,
    MANAGER,
    SPECIALIST,
}

@JvmInline
value class UserId(val value: UUID) {
    companion object {
        fun new(): UserId = UserId(UUID.randomUUID())
    }
}

data class Email(val value: String) {
    init {
        require(EMAIL_REGEX.matches(value)) { "Invalid email address: $value" }
    }

    companion object {
        private val EMAIL_REGEX = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    }
}

/**
 * Aggregate root for a ROJAN account. Construction is only possible through
 * [register] (email/password), [registerWithPhone] (OTP-based, Mobile-First
 * Authentication Phase 1), or [reconstitute] (rehydration from storage) so
 * invariants can never be bypassed.
 *
 * [email]/[passwordHash] and [phoneNumber] are each independently optional -
 * [register] always sets the former, [registerWithPhone] always sets the
 * latter, and either factory leaves the other null. The invariant "at least
 * one identity anchor exists" is enforced in both factories (and mirrored as
 * a DB-level CHECK constraint - see `V5__mobile_authentication.sql`), never
 * relaxed to "both may be null."
 */
class User private constructor(
    val id: UserId,
    email: Email?,
    passwordHash: String?,
    phoneNumber: PhoneNumber?,
    fullName: String,
    val role: UserRole,
    active: Boolean,
    val createdAt: Instant,
    updatedAt: Instant,
) {
    var email: Email? = email
        private set

    var passwordHash: String? = passwordHash
        private set

    var phoneNumber: PhoneNumber? = phoneNumber
        private set

    var fullName: String = fullName
        private set

    var active: Boolean = active
        private set

    var updatedAt: Instant = updatedAt
        private set

    fun rename(newFullName: String) {
        require(newFullName.isNotBlank()) { "Full name must not be blank" }
        fullName = newFullName.trim()
        updatedAt = Instant.now()
    }

    fun deactivate() {
        if (!active) return
        active = false
        updatedAt = Instant.now()
    }

    fun changePasswordHash(newHash: String) {
        require(newHash.isNotBlank()) { "Password hash must not be blank" }
        passwordHash = newHash
        updatedAt = Instant.now()
    }

    companion object {
        fun register(
            email: Email,
            passwordHash: String,
            fullName: String,
            role: UserRole,
        ): User {
            require(fullName.isNotBlank()) { "Full name must not be blank" }
            require(passwordHash.isNotBlank()) { "Password hash must not be blank" }
            val now = Instant.now()
            return User(
                id = UserId.new(),
                email = email,
                passwordHash = passwordHash,
                phoneNumber = null,
                fullName = fullName.trim(),
                role = role,
                active = true,
                createdAt = now,
                updatedAt = now,
            )
        }

        /** Mobile-First Authentication Phase 1: creates a phone-only account with no password - only reachable after a real OTP has already been verified (see `application/auth/VerifyOtpUseCase`), so there is no separate credential to validate here. */
        fun registerWithPhone(
            phoneNumber: PhoneNumber,
            fullName: String,
            role: UserRole,
        ): User {
            require(fullName.isNotBlank()) { "Full name must not be blank" }
            val now = Instant.now()
            return User(
                id = UserId.new(),
                email = null,
                passwordHash = null,
                phoneNumber = phoneNumber,
                fullName = fullName.trim(),
                role = role,
                active = true,
                createdAt = now,
                updatedAt = now,
            )
        }

        fun reconstitute(
            id: UserId,
            email: Email?,
            passwordHash: String?,
            phoneNumber: PhoneNumber? = null,
            fullName: String,
            role: UserRole,
            active: Boolean,
            createdAt: Instant,
            updatedAt: Instant,
        ): User = User(id, email, passwordHash, phoneNumber, fullName, role, active, createdAt, updatedAt)
    }
}
