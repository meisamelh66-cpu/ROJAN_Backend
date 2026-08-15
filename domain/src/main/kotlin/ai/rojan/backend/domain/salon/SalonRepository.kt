package ai.rojan.backend.domain.salon

import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.common.SortDirection
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
    fun findBySlug(slug: String): Salon?
    fun existsBySlug(slug: String): Boolean

    /** Browses active salons, optionally filtered by a case-insensitive name substring, sorted by name. */
    fun findAllActive(pageRequest: PageRequest, nameFilter: String?, sortDirection: SortDirection): PageResult<Salon>
}
