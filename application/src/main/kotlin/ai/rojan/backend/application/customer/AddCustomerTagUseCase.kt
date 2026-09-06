package ai.rojan.backend.application.customer

import ai.rojan.backend.domain.common.CustomerAccessDeniedException
import ai.rojan.backend.domain.common.CustomerNotFoundException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.customer.CustomerActivity
import ai.rojan.backend.domain.customer.CustomerActivityRepository
import ai.rojan.backend.domain.customer.CustomerActivityType
import ai.rojan.backend.domain.customer.CustomerId
import ai.rojan.backend.domain.customer.CustomerRepository
import ai.rojan.backend.domain.customer.CustomerTag
import ai.rojan.backend.domain.customer.CustomerTagRepository
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId

data class AddCustomerTagCommand(val customerId: CustomerId, val callerId: UserId, val label: String)

class AddCustomerTagUseCase(
    private val salonRepository: SalonRepository,
    private val customerRepository: CustomerRepository,
    private val customerTagRepository: CustomerTagRepository,
    private val customerActivityRepository: CustomerActivityRepository,
) {
    fun execute(command: AddCustomerTagCommand): CustomerTag {
        val customer = customerRepository.findById(command.customerId)
            ?: throw CustomerNotFoundException(command.customerId.value.toString())
        val salon = salonRepository.findById(customer.salonId)
            ?: throw SalonNotFoundException(customer.salonId.value.toString())
        if (salon.ownerId != command.callerId) {
            throw CustomerAccessDeniedException(customer.id.value.toString())
        }

        val tag = customerTagRepository.save(CustomerTag.create(customer.id, command.label))
        customerActivityRepository.save(
            CustomerActivity.create(customer.id, CustomerActivityType.TAG_ADDED, "Tag added: ${tag.label}"),
        )
        return tag
    }
}
