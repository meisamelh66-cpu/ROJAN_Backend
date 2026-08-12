package ai.rojan.backend.application.customer

import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.common.CustomerNotFoundException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.customer.CustomerId
import ai.rojan.backend.domain.customer.CustomerNote
import ai.rojan.backend.domain.customer.CustomerNoteRepository
import ai.rojan.backend.domain.customer.CustomerRepository
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId

data class AddCustomerNoteCommand(val customerId: CustomerId, val callerId: UserId, val text: String)

class AddCustomerNoteUseCase(
    private val salonRepository: SalonRepository,
    private val customerRepository: CustomerRepository,
    private val customerNoteRepository: CustomerNoteRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: AddCustomerNoteCommand): CustomerNote {
        val customer = customerRepository.findById(command.customerId)
            ?: throw CustomerNotFoundException(command.customerId.value.toString())
        val salon = salonRepository.findById(customer.salonId)
            ?: throw SalonNotFoundException(customer.salonId.value.toString())
        salonPermissionResolver.require(salon.id, command.callerId, Permission.MANAGE_CRM)

        val note = CustomerNote.create(customer.id, command.callerId, command.text)
        return customerNoteRepository.save(note)
    }
}
