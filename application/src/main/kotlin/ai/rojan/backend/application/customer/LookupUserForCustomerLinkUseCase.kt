package ai.rojan.backend.application.customer

import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.common.CustomerNotFoundException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.common.UserNotFoundException
import ai.rojan.backend.domain.customer.CustomerId
import ai.rojan.backend.domain.customer.CustomerRepository
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository

data class LookupUserForCustomerLinkCommand(
    val customerId: CustomerId,
    val callerId: UserId,
)

data class UserLinkCandidate(
    val userId: UserId,
    val fullName: String,
    val phoneNumber: String,
)

/**
 * Read-only counterpart to [LinkCustomerToUserUseCase] - resolves the one `User` account (if any)
 * matching an existing unlinked [ai.rojan.backend.domain.customer.Customer]'s own already-on-file
 * phone number, so a Manager can visually confirm the match before separately calling the real
 * link mutation. Deliberately **not** a general phone lookup: the phone number is always read from
 * the [CustomerId] this use case is given, never accepted from the caller - there is no way to
 * invoke this against an arbitrary phone number, which is exactly what keeps it from becoming a
 * global User directory or an enumeration tool (see `ROJAN_Customer_CRM_Architecture_Plan_v1.md`
 * §1.1/§4 - every Customer-adjacent capability in this controller is scoped to data the caller's
 * salon already legitimately holds, never the global `User` table at large).
 *
 * An inactive match is treated identically to no match ([UserNotFoundException]) - this use case
 * never discloses whether a deactivated account exists, mirroring [LinkCustomerToUserUseCase]'s own
 * rejection of linking to an inactive account.
 *
 * Never touches [ai.rojan.backend.domain.customer.Customer.linkToUser] or persists anything - pure
 * read.
 */
class LookupUserForCustomerLinkUseCase(
    private val salonRepository: SalonRepository,
    private val customerRepository: CustomerRepository,
    private val userRepository: UserRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: LookupUserForCustomerLinkCommand): UserLinkCandidate {
        val customer = customerRepository.findById(command.customerId)
            ?: throw CustomerNotFoundException(command.customerId.value.toString())
        val salon = salonRepository.findById(customer.salonId)
            ?: throw SalonNotFoundException(customer.salonId.value.toString())
        salonPermissionResolver.require(salon.id, command.callerId, Permission.MANAGE_CRM)

        val phoneNumber = customer.phoneNumber
            ?: throw UserNotFoundException(customer.id.value.toString())

        val user = userRepository.findByPhoneNumber(phoneNumber)
            ?.takeIf { it.active }
            ?: throw UserNotFoundException(customer.id.value.toString())

        return UserLinkCandidate(userId = user.id, fullName = user.fullName, phoneNumber = phoneNumber.value)
    }
}
