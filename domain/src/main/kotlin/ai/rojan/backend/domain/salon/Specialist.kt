package ai.rojan.backend.domain.salon

import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.user.UserId
import java.time.Instant
import java.util.UUID

@JvmInline
value class SpecialistId(val value: UUID) {
    companion object {
        fun new(): SpecialistId = SpecialistId(UUID.randomUUID())
    }
}

/**
 * A staff profile within a [Salon]. [userId] is optional so a salon can list
 * staff before they have (or ever get) an app account; when present it links
 * to a [ai.rojan.backend.domain.user.User] for future self-service login.
 */
class Specialist private constructor(
    val id: SpecialistId,
    val salonId: SalonId,
    val userId: UserId?,
    displayName: String,
    bio: String?,
    photoUrl: String?,
    mobileNumber: PhoneNumber?,
    specialty: String?,
    active: Boolean,
    val createdAt: Instant,
    updatedAt: Instant,
) {
    var displayName: String = displayName
        private set

    var bio: String? = bio
        private set

    var photoUrl: String? = photoUrl
        private set

    // Nullable here to accommodate specialists reconstituted from rows
    // created before V15 added these columns; CreateSpecialistCommand /
    // UpdateSpecialistCommand require real values for every specialist
    // created or edited going forward (see SpecialistDtos.kt).
    var mobileNumber: PhoneNumber? = mobileNumber
        private set

    var specialty: String? = specialty
        private set

    var active: Boolean = active
        private set

    var updatedAt: Instant = updatedAt
        private set

    /**
     * Full-replace update, matching the existing displayName/bio/photoUrl
     * semantics. mobileNumber/specialty are non-null here (not optional
     * like bio/photoUrl) so an update can never silently wipe out contact
     * info a specialist already has.
     */
    fun update(displayName: String, bio: String?, photoUrl: String?, mobileNumber: PhoneNumber, specialty: String) {
        require(displayName.isNotBlank()) { "Specialist display name must not be blank" }
        require(specialty.isNotBlank()) { "Specialist specialty must not be blank" }
        this.displayName = displayName.trim()
        this.bio = bio?.trim()?.ifBlank { null }
        this.photoUrl = photoUrl?.trim()?.ifBlank { null }
        this.mobileNumber = mobileNumber
        this.specialty = specialty.trim()
        this.updatedAt = Instant.now()
    }

    fun deactivate() {
        if (!active) return
        active = false
        updatedAt = Instant.now()
    }

    companion object {
        // mobileNumber/specialty default to null so the ~10 unrelated
        // booking/schedule test fixtures across the codebase that build a
        // throwaway Specialist via this factory keep compiling unchanged.
        // CreateSpecialistCommand (the real, API-reachable path) requires
        // non-null values and always supplies them here.
        fun create(
            salonId: SalonId,
            userId: UserId?,
            displayName: String,
            bio: String?,
            photoUrl: String?,
            mobileNumber: PhoneNumber? = null,
            specialty: String? = null,
        ): Specialist {
            require(displayName.isNotBlank()) { "Specialist display name must not be blank" }
            val now = Instant.now()
            return Specialist(
                id = SpecialistId.new(),
                salonId = salonId,
                userId = userId,
                displayName = displayName.trim(),
                bio = bio?.trim()?.ifBlank { null },
                photoUrl = photoUrl?.trim()?.ifBlank { null },
                mobileNumber = mobileNumber,
                specialty = specialty?.trim()?.ifBlank { null },
                active = true,
                createdAt = now,
                updatedAt = now,
            )
        }

        fun reconstitute(
            id: SpecialistId,
            salonId: SalonId,
            userId: UserId?,
            displayName: String,
            bio: String?,
            photoUrl: String?,
            mobileNumber: PhoneNumber?,
            specialty: String?,
            active: Boolean,
            createdAt: Instant,
            updatedAt: Instant,
        ): Specialist = Specialist(id, salonId, userId, displayName, bio, photoUrl, mobileNumber, specialty, active, createdAt, updatedAt)
    }
}
