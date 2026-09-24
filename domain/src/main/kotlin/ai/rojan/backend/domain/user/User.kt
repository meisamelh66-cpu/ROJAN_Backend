package ai.rojan.backend.domain.user

import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.media.MediaAssetId
import java.time.Instant
import java.util.UUID

enum class UserRole {
    CUSTOMER,
    MANAGER,
    SPECIALIST,

    /**
     * Platform-level actors (Super Admin - ROJAN Web only, never Android, never
     * a fourth app flavor). Deliberately never salon-scoped: authorization for
     * these two values is always checked directly against [User.role], never
     * through [ai.rojan.backend.domain.salon.SalonMembership]/
     * [ai.rojan.backend.application.salon.SalonPermissionResolver] - a platform
     * reviewer or admin has no [ai.rojan.backend.domain.salon.SalonMembership]
     * row of their own and needs none.
     */
    PLATFORM_REVIEWER,

    /** Full platform authority, including managing [PLATFORM_REVIEWER] accounts - see [PLATFORM_REVIEWER]'s own doc comment for the same salon-scoping rule. */
    PLATFORM_ADMIN,
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
 *
 * [avatarMediaId] / [coverMediaId] (Phase 5A.2, User Profile Media)
 * reference a USER-owned [ai.rojan.backend.domain.media.MediaAsset]; `null`
 * means "not set." Mirrors the `salons.logo_media_id` / `cover_media_id`
 * identity-slot pattern on [ai.rojan.backend.domain.salon.Salon] - the
 * reference lives here, the media's URL/bytes do not.
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
    avatarMediaId: MediaAssetId?,
    coverMediaId: MediaAssetId?,
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

    var avatarMediaId: MediaAssetId? = avatarMediaId
        private set

    var coverMediaId: MediaAssetId? = coverMediaId
        private set

    fun rename(newFullName: String) {
        require(newFullName.isNotBlank()) { "Full name must not be blank" }
        fullName = newFullName.trim()
        updatedAt = Instant.now()
    }

    /** Point the avatar slot at an already-stored [MediaAssetId], or `null` to clear it. Phase 5A.2. */
    fun assignAvatarMedia(mediaId: MediaAssetId?) {
        if (avatarMediaId == mediaId) return
        avatarMediaId = mediaId
        updatedAt = Instant.now()
    }

    /** Point the profile-cover slot at an already-stored [MediaAssetId], or `null` to clear it. Phase 5A.2. */
    fun assignCoverMedia(mediaId: MediaAssetId?) {
        if (coverMediaId == mediaId) return
        coverMediaId = mediaId
        updatedAt = Instant.now()
    }

    fun deactivate() {
        if (!active) return
        active = false
        updatedAt = Instant.now()
    }

    /** Platform Roles: the reverse of [deactivate] - e.g. a [PLATFORM_ADMIN] restoring a [PLATFORM_REVIEWER] account it previously deactivated. Idempotent, same shape as [deactivate]. */
    fun reactivate() {
        if (active) return
        active = true
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
                avatarMediaId = null,
                coverMediaId = null,
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
                avatarMediaId = null,
                coverMediaId = null,
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
            avatarMediaId: MediaAssetId? = null,
            coverMediaId: MediaAssetId? = null,
        ): User = User(
            id, email, passwordHash, phoneNumber, fullName, role, active, createdAt, updatedAt, avatarMediaId, coverMediaId,
        )
    }
}
