package ai.rojan.backend.domain.user

import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.common.SortDirection

/**
 * Output port for user persistence. Implemented by an adapter in the
 * infrastructure module (repository pattern) — the domain layer never
 * depends on a persistence framework.
 */
interface UserRepository {
    fun save(user: User): User
    fun findById(id: UserId): User?
    fun findByEmail(email: Email): User?
    fun existsByEmail(email: Email): Boolean

    /** Mobile-First Authentication Phase 1. */
    fun findByPhoneNumber(phoneNumber: PhoneNumber): User?

    fun existsByPhoneNumber(phoneNumber: PhoneNumber): Boolean

    /** Platform Roles - backs the reviewer-roster listing ([UserRole.PLATFORM_REVIEWER]/[UserRole.PLATFORM_ADMIN]), a small, unpaginated roster by design. [User.active] is already carried on every returned [User], so no separate active/inactive-filtered variant is needed. */
    fun findByRole(role: UserRole): List<User>

    /**
     * Platform Management API Contract (Manager/Customer): the same [role] filter as [findByRole]
     * above, but paginated and optionally name/phone-searched - for [UserRole.MANAGER]/
     * [UserRole.CUSTOMER] rosters, which are unbounded in size unlike the small platform-reviewer
     * roster [findByRole] serves. [search] matches a case-insensitive substring of [User.fullName]
     * or [User.phoneNumber]; `null`/blank means unfiltered. Sorted by [User.fullName], same
     * single-curated-sort-key convention [ai.rojan.backend.domain.salon.SalonRepository.findAllActive]
     * already establishes.
     */
    fun findByRole(role: UserRole, pageRequest: PageRequest, search: String?, sortDirection: SortDirection): PageResult<User>
}
