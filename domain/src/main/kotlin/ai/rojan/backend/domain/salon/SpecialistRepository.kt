package ai.rojan.backend.domain.salon

import ai.rojan.backend.domain.user.UserId

interface SpecialistRepository {
    fun save(specialist: Specialist): Specialist
    fun findById(id: SpecialistId): Specialist?
    fun findBySalonId(salonId: SalonId): List<Specialist>

    /** Used by `SalonPermissionResolver` to check whether the caller is the linked account for a specialist at this salon. */
    fun findBySalonIdAndUserId(salonId: SalonId, userId: UserId): Specialist?

    /** Every specialist link across every salon for this user - powers `GET /api/v1/users/me/salon-access`. */
    fun findByUserId(userId: UserId): List<Specialist>
}
