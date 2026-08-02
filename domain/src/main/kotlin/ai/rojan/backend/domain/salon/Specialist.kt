package ai.rojan.backend.domain.salon

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

    var active: Boolean = active
        private set

    var updatedAt: Instant = updatedAt
        private set

    fun update(displayName: String, bio: String?, photoUrl: String?) {
        require(displayName.isNotBlank()) { "Specialist display name must not be blank" }
        this.displayName = displayName.trim()
        this.bio = bio?.trim()?.ifBlank { null }
        this.photoUrl = photoUrl?.trim()?.ifBlank { null }
        this.updatedAt = Instant.now()
    }

    fun deactivate() {
        if (!active) return
        active = false
        updatedAt = Instant.now()
    }

    companion object {
        fun create(
            salonId: SalonId,
            userId: UserId?,
            displayName: String,
            bio: String?,
            photoUrl: String?,
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
            active: Boolean,
            createdAt: Instant,
            updatedAt: Instant,
        ): Specialist = Specialist(id, salonId, userId, displayName, bio, photoUrl, active, createdAt, updatedAt)
    }
}
