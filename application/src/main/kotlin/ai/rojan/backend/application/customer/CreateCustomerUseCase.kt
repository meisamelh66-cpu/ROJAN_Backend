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
)

/**
 * Creates a CRM record with no linked account (owner adds a walk-in/manual
 * customer) - see [Customer]'s own doc comment for why that is a first-class
 * case, not a workaround. [Customer.userId] stays null; a future
 * reconciliation step (out of Phase 1 scope, see
 * `ROJAN_Customer_CRM_Architecture_Plan_v1.md` §6.4) is the only way to
 * link it to a real account later.
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
            throw CustomerAlreadyExistsException(phoneNumber.value)
        }

        val customer = Customer.create(
            salonId = salon.id,
            userId = null,
            fullName = command.fullName,
            phoneNumber = phoneNumber,
            email = email,
            company = command.company,
        )
        return customerRepository.save(customer)
    }
}
