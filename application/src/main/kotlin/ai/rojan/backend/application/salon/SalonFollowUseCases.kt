package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.salon.SalonFollow
import ai.rojan.backend.domain.salon.SalonFollowRepository
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId

data class FollowSalonCommand(val customerId: UserId, val salonId: SalonId)

/**
 * Idempotent: following an already-followed salon returns the existing row
 * unchanged rather than erroring - "follow" is a state the caller asserts,
 * not a one-shot action that can conflict. Re-following after an unfollow
 * reactivates the same row instead of creating a duplicate.
 */
class FollowSalonUseCase(
    private val salonRepository: SalonRepository,
    private val followRepository: SalonFollowRepository,
) {
    fun execute(command: FollowSalonCommand): SalonFollow {
        salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())

        val existing = followRepository.findByCustomerIdAndSalonId(command.customerId, command.salonId)
        if (existing != null) {
            if (!existing.isActive) existing.reactivate()
            return followRepository.save(existing)
        }
        return followRepository.save(SalonFollow.create(command.customerId, command.salonId))
    }
}

data class UnfollowSalonCommand(val customerId: UserId, val salonId: SalonId)

/** Idempotent: unfollowing a salon that was never followed (or already unfollowed) is a no-op - DELETE semantics. */
class UnfollowSalonUseCase(
    private val followRepository: SalonFollowRepository,
) {
    fun execute(command: UnfollowSalonCommand) {
        val existing = followRepository.findByCustomerIdAndSalonId(command.customerId, command.salonId) ?: return
        if (existing.isActive) {
            existing.remove()
            followRepository.save(existing)
        }
    }
}

data class ListFollowedSalonsQuery(val customerId: UserId, val pageRequest: PageRequest)

/** Only ever reads [ListFollowedSalonsQuery.customerId]'s own rows - there is no target-customer parameter to spoof, so cross-tenant leakage is structurally impossible here, not just permission-checked. */
class ListFollowedSalonsUseCase(
    private val followRepository: SalonFollowRepository,
) {
    fun execute(query: ListFollowedSalonsQuery): PageResult<SalonFollow> =
        followRepository.findActiveByCustomerId(query.customerId, query.pageRequest)
}
