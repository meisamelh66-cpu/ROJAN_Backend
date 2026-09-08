package ai.rojan.backend.application.customer

import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.CustomerAlreadyExistsException
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.customer.Customer
import ai.rojan.backend.domain.customer.CustomerRepository
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.Email
import ai.rojan.backend.domain.user.UserId

data class CreateCustomerCommand(
    val salonId: SalonId,
    val callerId: UserId,
    val fullName: String,
    val phoneNumber: String?,
    val email: String?,
    val company: String?,
    /**
     * BACKEND-CRM-CUSTOMER-IDENTITY-001: optional link to a real backend
     * account. `null` keeps the original walk-in/manual behaviour (the
     * common owner-adds-a-customer path). When non-null the new record is
     * linked, and the salon must not already have a record for that account.
     */
    val userId: UserId? = null,
)

/**
 * Creates a salon's CRM record for a person. [CreateCustomerCommand.userId]
 * is normally `null` - a walk-in/manual customer with no app account (see
 * [Customer]'s own doc comment for why that is a first-class case, not a
 * workaround). BACKEND-CRM-CUSTOMER-IDENTITY-001 additionally allows an
 * explicit account link at creation time; `ResolveOrCreateSalonCustomerUseCase`
 * is the automatic path the booking flow uses.
 */
class CreateCustomerUseCase(
    private val salonRepository: SalonRepository,
    private val customerRepository: CustomerRepository,
) {
    fun execute(command: CreateCustomerCommand): Customer {
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        if (salon.ownerId != command.callerId) {
            throw SalonAccessDeniedException(salon.id.value.toString())
        }

        val phoneNumber = command.phoneNumber?.let { PhoneNumber(it) }
        val email = command.email?.let { Email(it) }

        if (phoneNumber != null && customerRepository.existsBySalonIdAndPhoneNumber(salon.id, phoneNumber)) {
            throw CustomerAlreadyExistsException.forPhoneNumber(phoneNumber.value)
        }
        if (command.userId != null && customerRepository.findBySalonIdAndUserId(salon.id, command.userId) != null) {
            throw CustomerAlreadyExistsException.forLinkedAccount(command.userId.value.toString())
        }

        val customer = Customer.create(
            salonId = salon.id,
            userId = command.userId,
            fullName = command.fullName,
            phoneNumber = phoneNumber,
            email = email,
            company = command.company,
        )
        return customerRepository.save(customer)
    }
}
