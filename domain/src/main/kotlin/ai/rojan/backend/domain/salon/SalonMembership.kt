package ai.rojan.backend.domain.salon

import ai.rojan.backend.domain.user.UserId
import java.time.Instant
import java.util.UUID

@JvmInline
value class SalonMembershipId(val value: UUID) {
    companion object {
        fun new(): SalonMembershipId = SalonMembershipId(UUID.randomUUID())
    }
}

/**
 * Grants [userId] [role]'s permissions at [salonId] - a real, salon-scoped
 * role, deliberately distinct from the global `UserRole` on `User` (which
 * describes an account type, not what a person is authorized to do at any
 * particular salon) and from [Salon.ownerId] (which is never represented as
 * a membership row - see [SalonRole]'s doc comment). One row per
 * (salon, user) pair - `assign` upserts the role rather than allowing
 * duplicates.
 */
class SalonMembership private constructor(
    val id: SalonMembershipId,
    val salonId: SalonId,
    val userId: UserId,
    role: SalonRole,
    val createdAt: Instant,
    updatedAt: Instant,
) {
    var role: SalonRole = role
        private set

    var updatedAt: Instant = updatedAt
        private set

    fun changeRole(newRole: SalonRole) {
        this.role = newRole
        this.updatedAt = Instant.now()
    }

    companion object {
        fun create(salonId: SalonId, userId: UserId, role: SalonRole): SalonMembership {
            val now = Instant.now()
            return SalonMembership(SalonMembershipId.new(), salonId, userId, role, now, now)
        }

        fun reconstitute(
            id: SalonMembershipId,
            salonId: SalonId,
            userId: UserId,
            role: SalonRole,
            createdAt: Instant,
            updatedAt: Instant,
        ): SalonMembership = SalonMembership(id, salonId, userId, role, createdAt, updatedAt)
    }
}

interface SalonMembershipRepository {
    /** Upserts - creates a new membership, or changes the role if one already exists for this (salonId, userId) pair. */
    fun assign(salonId: SalonId, userId: UserId, role: SalonRole): SalonMembership
    fun remove(salonId: SalonId, userId: UserId)
    fun findBySalonIdAndUserId(salonId: SalonId, userId: UserId): SalonMembership?
    fun findBySalonId(salonId: SalonId): List<SalonMembership>

    /** Every membership across every salon for this user - powers `GET /api/v1/users/me/salon-access`. */
    fun findByUserId(userId: UserId): List<SalonMembership>
}
