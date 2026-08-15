package ai.rojan.backend.application.customer

import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.CustomerNotFoundException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.customer.Customer
import ai.rojan.backend.domain.customer.CustomerActivity
import ai.rojan.backend.domain.customer.CustomerActivityRepository
import ai.rojan.backend.domain.customer.CustomerActivityType
import ai.rojan.backend.domain.customer.CustomerId
import ai.rojan.backend.domain.customer.CustomerRepository
import ai.rojan.backend.domain.customer.CustomerStatus
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.Email
import ai.rojan.backend.domain.user.UserId

/**
 * Every field is nullable and means "leave unchanged" when absent - genuine
 * PATCH semantics, not [ai.rojan.backend.application.salon.UpdateSpecialistUseCase]'s
 * full-replace-via-PUT pattern. [fullName]/[phoneNumber]/[email]/[company]
 * merge onto the current record; a caller cannot explicitly clear
 * [company] back to null this way (a reasonable Phase 1 simplification -
 * `phoneNumber`/`email` can't be cleared either without violating the
 * "at least one contact method" invariant, so a merge-only PATCH is
 * consistent across every field, not just company).
 */
data class UpdateCustomerCommand(
    val customerId: CustomerId,
    val callerId: UserId,
    val fullName: String?,
    val phoneNumber: String?,
    val email: String?,
    val company: String?,
    val status: CustomerStatus?,
)

class UpdateCustomerUseCase(
    private val salonRepository: SalonRepository,
    private val customerRepository: CustomerRepository,
    private val customerActivityRepository: CustomerActivityRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: UpdateCustomerCommand): Customer {
        val customer = customerRepository.findById(command.customerId)
            ?: throw CustomerNotFoundException(command.customerId.value.toString())
        val salon = salonRepository.findById(customer.salonId)
            ?: throw SalonNotFoundException(customer.salonId.value.toString())
        salonPermissionResolver.require(salon.id, command.callerId, Permission.MANAGE_CRM)

        customer.update(
            fullName = command.fullName ?: customer.fullName,
            phoneNumber = command.phoneNumber?.let { PhoneNumber(it) } ?: customer.phoneNumber,
            email = command.email?.let { Email(it) } ?: customer.email,
            company = command.company ?: customer.company,
        )

        if (command.status != null && command.status != customer.status) {
            val previousStatus = customer.status
            customer.changeStatus(command.status)
            customerActivityRepository.save(
                CustomerActivity.create(
                    customerId = customer.id,
                    type = CustomerActivityType.STATUS_CHANGED,
                    description = "Status changed from $previousStatus to ${command.status}",
                ),
            )
        }

        return customerRepository.save(customer)
    }
}
