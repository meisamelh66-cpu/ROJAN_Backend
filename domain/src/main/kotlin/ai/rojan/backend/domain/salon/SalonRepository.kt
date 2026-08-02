package ai.rojan.backend.domain.salon

import ai.rojan.backend.domain.user.UserId

/**
 * Output port for salon persistence. Implemented by an adapter in the
 * infrastructure module (repository pattern) — the domain layer never
 * depends on a persistence framework.
 */
interface SalonRepository {
    fun save(salon: Salon): Salon
    fun findById(id: SalonId): Salon?
    fun findByOwnerId(ownerId: UserId): List<Salon>
    fun findAllActive(): List<Salon>
}
