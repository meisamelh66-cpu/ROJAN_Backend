package ai.rojan.backend.application.customer

import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.common.CustomerAlreadyLinkedException
import ai.rojan.backend.domain.common.CustomerNotFoundException
import ai.rojan.backend.domain.common.InactiveUserException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.common.UserAlreadyLinkedToCustomerException
import ai.rojan.backend.domain.common.UserNotFoundException
import ai.rojan.backend.domain.customer.Customer
import ai.rojan.backend.domain.customer.CustomerActivity
import ai.rojan.backend.domain.customer.CustomerActivityRepository
import ai.rojan.backend.domain.customer.CustomerActivityType
import ai.rojan.backend.domain.customer.CustomerId
import ai.rojan.backend.domain.customer.CustomerRepository
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository

data class LinkCustomerToUserCommand(
    val customerId: CustomerId,
    val callerId: UserId,
    val userId: UserId,
)

/**
 * Explicit, Owner/Manager-initiated reconciliation of an existing unlinked walk-in [Customer]
 * record to a real [ai.rojan.backend.domain.user.User] account (`ROJAN_Customer_CRM_Architecture_Plan_v1.md`
 * §6.4). Deliberately a one-shot, manually-triggered action - never fuzzy/automatic matching by
 * email or phone, and never a silent overwrite of an already-linked record (a caller who wants to
 * relink must first be a separate, explicit decision this use case does not make).
 *
 * The pre-check against [CustomerRepository.findBySalonIdAndUserId] mirrors [CreateCustomerUseCase]'s
 * own phone-uniqueness pre-check: a best-effort guard, with `uq_customers_salon_user` (the partial
 * unique index already in production) as the real backstop for a genuine concurrent-link race.
 */
class LinkCustomerToUserUseCase(
    private val salonRepository: SalonRepository,
    private val customerRepository: CustomerRepository,
    private val userRepository: UserRepository,
    private val customerActivityRepository: CustomerActivityRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: LinkCustomerToUserCommand): Customer {
        val customer = customerRepository.findById(command.customerId)
            ?: throw CustomerNotFoundException(command.customerId.value.toString())
        val salon = salonRepository.findById(customer.salonId)
            ?: throw SalonNotFoundException(customer.salonId.value.toString())
        salonPermissionResolver.require(salon.id, command.callerId, Permission.MANAGE_CRM)

        if (customer.userId != null) {
            throw CustomerAlreadyLinkedException(customer.id.value.toString())
        }

        val user = userRepository.findById(command.userId)
            ?: throw UserNotFoundException(command.userId.value.toString())
        if (!user.active) {
            throw InactiveUserException(user.id.value.toString())
        }

        if (customerRepository.findBySalonIdAndUserId(salon.id, command.userId) != null) {
            throw UserAlreadyLinkedToCustomerException(command.userId.value.toString(), salon.id.value.toString())
        }

        customer.linkToUser(command.userId)
        val saved = customerRepository.save(customer)

        customerActivityRepository.save(
            CustomerActivity.create(
                customerId = customer.id,
                type = CustomerActivityType.USER_LINKED,
                description = "Linked to user account ${command.userId.value}",
            ),
        )

        return saved
    }
}
