package ai.rojan.backend.application.customer

import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.CustomerAlreadyExistsException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.customer.Customer
import ai.rojan.backend.domain.customer.CustomerRepository
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.Email
import ai.rojan.backend.domain.user.UserId

data class CreateCustomerIdentityCommand(
    val salonId: SalonId,
    val callerId: UserId,
    val fullName: String,
    val phoneNumber: String?,
    val email: String?,
)

/**
 * Booking-time customer registration - the Reception-facing counterpart to
 * [CreateCustomerUseCase], per `ROJAN_Reception_Permission_Contract_Update_ADR_v1.md`.
 * Structurally narrower, not just permission-gated differently: no `company`
 * parameter exists on [CreateCustomerIdentityCommand] to even omit by
 * convention - a caller holding only [Permission.CREATE_CUSTOMER_IDENTITY]
 * (no [Permission.MANAGE_CRM]) has no way to submit one.
 */
class CreateCustomerIdentityUseCase(
    private val salonRepository: SalonRepository,
    private val customerRepository: CustomerRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: CreateCustomerIdentityCommand): Customer {
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        salonPermissionResolver.requireAny(salon.id, command.callerId, Permission.CREATE_CUSTOMER_IDENTITY, Permission.MANAGE_CRM)

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
            company = null,
        )
        return customerRepository.save(customer)
    }
}
