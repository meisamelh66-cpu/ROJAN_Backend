package ai.rojan.backend.application.platformauthority

import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.common.SortDirection
import ai.rojan.backend.domain.common.UserNotFoundException
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonMembershipRepository
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.SalonRole
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository
import ai.rojan.backend.domain.user.UserRole

/**
 * Platform Management API Contract (Manager): read-first oversight of [UserRole.MANAGER] accounts -
 * PLATFORM_ADMIN or PLATFORM_REVIEWER may list, PLATFORM_ADMIN only may deactivate/reactivate
 * (mirrors [ListPlatformReviewersUseCase]/[DeactivatePlatformReviewerUseCase]'s own read-vs-mutate
 * split, applied here for the first time to a non-platform role). Deliberately exposes salon
 * *associations* only (which salons, and as owner or which [SalonRole]) - never salon-private
 * operational permissions, catalog/staff/CRM data, or anything beyond identity - see this file's own
 * design report for the full field-visibility boundary.
 */

/** Whether this manager reaches a salon as its owner ([ai.rojan.backend.domain.salon.Salon.ownerId]) or as a real [ai.rojan.backend.domain.salon.SalonMembership] row - the same two-source model [ai.rojan.backend.application.salon.ResolveMySalonAccessUseCase] already resolves for a user's own `/me/salon-access` view, applied here for platform oversight of someone else's account. */
enum class ManagerSalonAccessType { OWNER, MEMBER }

/** [role] is `null` for [ManagerSalonAccessType.OWNER] - ownership is never a [SalonRole] (see [SalonRole]'s own doc comment); always a real, non-null [SalonRole] for [ManagerSalonAccessType.MEMBER]. */
data class ManagerSalonAssociation(
    val salonId: SalonId,
    val salonName: String,
    val accessType: ManagerSalonAccessType,
    val role: SalonRole?,
)

data class PlatformManagerAccount(
    val user: User,
    val salonAssociations: List<ManagerSalonAssociation>,
)

data class ListPlatformManagersQuery(
    val callerId: UserId,
    val page: Int,
    val size: Int,
    val search: String?,
    val sortDirection: SortDirection,
)

/**
 * Resolves each manager's real salon associations by composing the same, already-existing
 * [SalonRepository.findByOwnerId]/[SalonMembershipRepository.findByUserId] queries
 * [ai.rojan.backend.application.salon.ResolveMySalonAccessUseCase] already uses for the self-service
 * `/me/salon-access` view - no new repository capability, applied per-row for up to one admin page
 * (`PageRequest.MAX_SIZE` = 100) of managers rather than for a single caller.
 */
class ListPlatformManagersUseCase(
    private val userRepository: UserRepository,
    private val salonRepository: SalonRepository,
    private val salonMembershipRepository: SalonMembershipRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(query: ListPlatformManagersQuery): PageResult<PlatformManagerAccount> {
        platformAuthorization.requirePlatformReviewerOrAdmin(query.callerId)

        val page = userRepository.findByRole(
            UserRole.MANAGER,
            PageRequest(query.page, query.size),
            query.search,
            query.sortDirection,
        )
        val accounts = page.content.map { user ->
            val owned = salonRepository.findByOwnerId(user.id).map { salon ->
                ManagerSalonAssociation(salon.id, salon.name, ManagerSalonAccessType.OWNER, null)
            }
            val memberships = salonMembershipRepository.findByUserId(user.id).mapNotNull { membership ->
                salonRepository.findById(membership.salonId)?.let { salon ->
                    ManagerSalonAssociation(salon.id, salon.name, ManagerSalonAccessType.MEMBER, membership.role)
                }
            }
            PlatformManagerAccount(user, owned + memberships)
        }
        return PageResult(content = accounts, page = page.page, size = page.size, totalElements = page.totalElements)
    }
}

data class DeactivatePlatformManagerCommand(val callerId: UserId, val managerId: UserId)

/** Admin-only. [User.deactivate] blocks login/refresh ([ai.rojan.backend.application.auth.AuthenticateUserUseCase]/[ai.rojan.backend.application.auth.RefreshTokenUseCase] both check [User.active]) - it never touches [ai.rojan.backend.domain.salon.Salon.active]/activation status for any salon this manager owns, a deliberately separate field on a deliberately separate aggregate. */
class DeactivatePlatformManagerUseCase(
    private val userRepository: UserRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(command: DeactivatePlatformManagerCommand): User {
        platformAuthorization.requirePlatformAdmin(command.callerId)
        val manager = findManager(command.managerId, userRepository)
        manager.deactivate()
        return userRepository.save(manager)
    }
}

data class ReactivatePlatformManagerCommand(val callerId: UserId, val managerId: UserId)

/** Admin-only. The reverse of [DeactivatePlatformManagerUseCase], same idempotent [User.reactivate] shape. */
class ReactivatePlatformManagerUseCase(
    private val userRepository: UserRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(command: ReactivatePlatformManagerCommand): User {
        platformAuthorization.requirePlatformAdmin(command.callerId)
        val manager = findManager(command.managerId, userRepository)
        manager.reactivate()
        return userRepository.save(manager)
    }
}

/** Same "exists-but-wrong-kind 404s identically to not-found" discipline [findReviewer] (`PlatformReviewerUseCases.kt`) already establishes - a valid user id that isn't a [UserRole.MANAGER] never leaks its real role to the caller. */
private fun findManager(managerId: UserId, userRepository: UserRepository): User =
    userRepository.findById(managerId)?.takeIf { it.role == UserRole.MANAGER }
        ?: throw UserNotFoundException(managerId.value.toString())
