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
    active: Boolean,
    val createdAt: Instant,
    updatedAt: Instant,
) {
    var role: SalonRole = role
        private set

    /**
     * Staff Access Phase 1 (Phone -> OTP -> User -> SalonMembership -> Role -> Permissions ->
     * Active/Disabled): a disabled membership keeps its [role] and row intact - [SalonPermissionResolver]
     * grants zero permissions for it - distinct from [SalonMembershipRepository.remove]'s hard delete,
     * which is reserved for true removal, not routine staff deactivation.
     */
    var active: Boolean = active
        private set

    var updatedAt: Instant = updatedAt
        private set

    fun changeRole(newRole: SalonRole) {
        this.role = newRole
        this.updatedAt = Instant.now()
    }

    fun activate() {
        this.active = true
        this.updatedAt = Instant.now()
    }

    fun deactivate() {
        this.active = false
        this.updatedAt = Instant.now()
    }

    companion object {
        fun create(salonId: SalonId, userId: UserId, role: SalonRole, active: Boolean = true): SalonMembership {
            val now = Instant.now()
            return SalonMembership(SalonMembershipId.new(), salonId, userId, role, active, now, now)
        }

        fun reconstitute(
            id: SalonMembershipId,
            salonId: SalonId,
            userId: UserId,
            role: SalonRole,
            active: Boolean,
            createdAt: Instant,
            updatedAt: Instant,
        ): SalonMembership = SalonMembership(id, salonId, userId, role, active, createdAt, updatedAt)
    }
}

interface SalonMembershipRepository {
    /** Upserts - creates a new membership, or changes the role if one already exists for this (salonId, userId) pair. Always leaves the membership active (a fresh grant, or a role change on an existing one, both count as "activate membership"). */
    fun assign(salonId: SalonId, userId: UserId, role: SalonRole): SalonMembership

    /** Hard delete - true removal, distinct from [setActive](salonId, userId, false) which is the normal "disable staff" path and keeps the row. */
    fun remove(salonId: SalonId, userId: UserId)

    /** Disables or re-enables an existing membership in place - never recreates it, never touches [SalonMembership.role]. Returns `null` if no membership exists for this (salonId, userId) pair. */
    fun setActive(salonId: SalonId, userId: UserId, active: Boolean): SalonMembership?
    fun findBySalonIdAndUserId(salonId: SalonId, userId: UserId): SalonMembership?
    fun findBySalonId(salonId: SalonId): List<SalonMembership>

    /** Every membership across every salon for this user - powers `GET /api/v1/users/me/salon-access`. */
    fun findByUserId(userId: UserId): List<SalonMembership>
}
