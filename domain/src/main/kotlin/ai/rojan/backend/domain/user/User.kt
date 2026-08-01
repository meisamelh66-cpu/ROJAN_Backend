package ai.rojan.backend.domain.user

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
 * [register] (new accounts) or [reconstitute] (rehydration from storage) so
 * invariants can never be bypassed.
 */
class User private constructor(
    val id: UserId,
    val email: Email,
    passwordHash: String,
    fullName: String,
    val role: UserRole,
    active: Boolean,
    val createdAt: Instant,
    updatedAt: Instant,
) {
    var passwordHash: String = passwordHash
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
                fullName = fullName.trim(),
                role = role,
                active = true,
                createdAt = now,
                updatedAt = now,
            )
        }

        fun reconstitute(
            id: UserId,
            email: Email,
            passwordHash: String,
            fullName: String,
            role: UserRole,
            active: Boolean,
            createdAt: Instant,
            updatedAt: Instant,
        ): User = User(id, email, passwordHash, fullName, role, active, createdAt, updatedAt)
    }
}
