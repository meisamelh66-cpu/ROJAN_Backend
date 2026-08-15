package ai.rojan.backend.domain.salon

import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.user.UserId
import java.time.Instant
import java.util.UUID

@JvmInline
value class SalonFollowId(val value: UUID) {
    companion object {
        fun new(): SalonFollowId = SalonFollowId(UUID.randomUUID())
    }
}

enum class SalonFollowStatus {
    ACTIVE,
    REMOVED,
}

/**
 * Records that [customerId] wants ongoing updates/news from [salonId] -
 * [customerId] is a real, authenticated app [ai.rojan.backend.domain.user.User]'s
 * id, deliberately NOT [ai.rojan.backend.domain.customer.CustomerId] (the
 * salon-owned CRM contact-book aggregate in `domain.customer`, which can
 * exist with no linked user account at all). Those are two different
 * "customer" concepts that happen to share an English word; this one is
 * customer self-service, that one is owner-facing CRM.
 *
 * Soft-deleted via [status] rather than a hard delete, so a follow -> unfollow
 * -> re-follow cycle (expected, ordinary customer behavior) restores the
 * same row and keeps its original [createdAt] rather than losing history -
 * mirrors [SalonMembership]'s "one row per pair" shape but with an explicit
 * status instead of removal-by-deletion.
 */
class SalonFollow private constructor(
    val id: SalonFollowId,
    val customerId: UserId,
    val salonId: SalonId,
    val createdAt: Instant,
    status: SalonFollowStatus,
) {
    var status: SalonFollowStatus = status
        private set

    val isActive: Boolean get() = status == SalonFollowStatus.ACTIVE

    fun reactivate() {
        status = SalonFollowStatus.ACTIVE
    }

    fun remove() {
        status = SalonFollowStatus.REMOVED
    }

    companion object {
        fun create(customerId: UserId, salonId: SalonId): SalonFollow =
            SalonFollow(SalonFollowId.new(), customerId, salonId, Instant.now(), SalonFollowStatus.ACTIVE)

        fun reconstitute(
            id: SalonFollowId,
            customerId: UserId,
            salonId: SalonId,
            createdAt: Instant,
            status: SalonFollowStatus,
        ): SalonFollow = SalonFollow(id, customerId, salonId, createdAt, status)
    }
}

/**
 * Output port for [SalonFollow] persistence. No `countActiveBySalonId`/
 * `followersCount`-style method here yet - deliberately not added ahead of
 * a real caller, per the product decision that a future Manager-facing
 * follower count must be a live aggregation over this table
 * (`COUNT(*) WHERE salon_id = ? AND status = 'ACTIVE'`), never a stored
 * counter column. Adding that query is a one-line change here when a real
 * use case needs it.
 */
interface SalonFollowRepository {
    fun save(follow: SalonFollow): SalonFollow
    fun findByCustomerIdAndSalonId(customerId: UserId, salonId: SalonId): SalonFollow?
    fun findActiveByCustomerId(customerId: UserId, pageRequest: PageRequest): PageResult<SalonFollow>
}
