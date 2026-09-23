package ai.rojan.backend.application.platformauthority

import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.common.SortDirection
import ai.rojan.backend.domain.common.UserNotFoundException
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository
import ai.rojan.backend.domain.user.UserRole

/**
 * Platform Management API Contract (Customer): read-first oversight of [UserRole.CUSTOMER]
 * **accounts** - deliberately named "CustomerAccount" throughout, never bare "Customer", to keep
 * this unmistakably distinct in code from [ai.rojan.backend.domain.customer.Customer] (a salon's
 * private CRM record for a person, which this file never touches, queries, or exposes). Same
 * read-vs-mutate authorization split as [ListPlatformManagersUseCase]/
 * [DeactivatePlatformManagerUseCase]: PLATFORM_ADMIN or PLATFORM_REVIEWER may list, PLATFORM_ADMIN
 * only may deactivate/reactivate.
 *
 * Exposes [User] identity fields only (id, phone, full name, active, createdAt) - no salon
 * association of any kind, unlike [PlatformManagerAccount]. A customer's relationship to a salon
 * only ever exists through the salon-private [ai.rojan.backend.domain.customer.Customer] CRM record
 * or a [ai.rojan.backend.domain.booking.Booking] - both explicitly out of scope, so there is
 * structurally nothing salon-shaped for this use case to resolve or leak.
 */
data class ListPlatformCustomerAccountsQuery(
    val callerId: UserId,
    val page: Int,
    val size: Int,
    val search: String?,
    val sortDirection: SortDirection,
)

class ListPlatformCustomerAccountsUseCase(
    private val userRepository: UserRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(query: ListPlatformCustomerAccountsQuery): PageResult<User> {
        platformAuthorization.requirePlatformReviewerOrAdmin(query.callerId)
        return userRepository.findByRole(
            UserRole.CUSTOMER,
            PageRequest(query.page, query.size),
            query.search,
            query.sortDirection,
        )
    }
}

data class DeactivatePlatformCustomerAccountCommand(val callerId: UserId, val customerAccountId: UserId)

/** Admin-only. [User.deactivate] blocks login/refresh only - never touches any salon's private CRM record for this person (there is none to touch; salon-private [ai.rojan.backend.domain.customer.Customer] rows are a completely separate aggregate this use case never queries). */
class DeactivatePlatformCustomerAccountUseCase(
    private val userRepository: UserRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(command: DeactivatePlatformCustomerAccountCommand): User {
        platformAuthorization.requirePlatformAdmin(command.callerId)
        val customerAccount = findCustomerAccount(command.customerAccountId, userRepository)
        customerAccount.deactivate()
        return userRepository.save(customerAccount)
    }
}

data class ReactivatePlatformCustomerAccountCommand(val callerId: UserId, val customerAccountId: UserId)

/** Admin-only. The reverse of [DeactivatePlatformCustomerAccountUseCase], same idempotent [User.reactivate] shape. */
class ReactivatePlatformCustomerAccountUseCase(
    private val userRepository: UserRepository,
    private val platformAuthorization: PlatformAuthorizationResolver,
) {
    fun execute(command: ReactivatePlatformCustomerAccountCommand): User {
        platformAuthorization.requirePlatformAdmin(command.callerId)
        val customerAccount = findCustomerAccount(command.customerAccountId, userRepository)
        customerAccount.reactivate()
        return userRepository.save(customerAccount)
    }
}

/** Same "exists-but-wrong-kind 404s identically to not-found" discipline [PlatformManagerUseCases.kt]'s `findManager` already establishes. */
private fun findCustomerAccount(customerAccountId: UserId, userRepository: UserRepository): User =
    userRepository.findById(customerAccountId)?.takeIf { it.role == UserRole.CUSTOMER }
        ?: throw UserNotFoundException(customerAccountId.value.toString())
