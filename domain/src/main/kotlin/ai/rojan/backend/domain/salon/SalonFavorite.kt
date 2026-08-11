package ai.rojan.backend.domain.salon

import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.user.UserId
import java.time.Instant
import java.util.UUID

@JvmInline
value class SalonFavoriteId(val value: UUID) {
    companion object {
        fun new(): SalonFavoriteId = SalonFavoriteId(UUID.randomUUID())
    }
}

/**
 * Records that [customerId] saved [salonId] for their own personal use -
 * deliberately a separate concept from [SalonFollow] (updates/news intent),
 * per the product decision not to merge the two. [customerId] is the same
 * app-user id as [SalonFollow.customerId] - see that class's doc comment for
 * why this is never [ai.rojan.backend.domain.customer.CustomerId].
 *
 * No status/soft-delete here, unlike [SalonFollow]: a favorite is either
 * present or gone. There is no "removed but keep the history" case a
 * favorite/unfavorite/re-favorite cycle needs to preserve.
 */
class SalonFavorite private constructor(
    val id: SalonFavoriteId,
    val customerId: UserId,
    val salonId: SalonId,
    val createdAt: Instant,
) {
    companion object {
        fun create(customerId: UserId, salonId: SalonId): SalonFavorite =
            SalonFavorite(SalonFavoriteId.new(), customerId, salonId, Instant.now())

        fun reconstitute(
            id: SalonFavoriteId,
            customerId: UserId,
            salonId: SalonId,
            createdAt: Instant,
        ): SalonFavorite = SalonFavorite(id, customerId, salonId, createdAt)
    }
}

/** Output port for [SalonFavorite] persistence. Same "no stored counter" reasoning as [SalonFollowRepository]. */
interface SalonFavoriteRepository {
    fun save(favorite: SalonFavorite): SalonFavorite
    fun findByCustomerIdAndSalonId(customerId: UserId, salonId: SalonId): SalonFavorite?
    fun deleteByCustomerIdAndSalonId(customerId: UserId, salonId: SalonId)
    fun findByCustomerId(customerId: UserId, pageRequest: PageRequest): PageResult<SalonFavorite>
}
