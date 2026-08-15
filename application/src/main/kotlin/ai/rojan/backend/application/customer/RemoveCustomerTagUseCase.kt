package ai.rojan.backend.application.customer

import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.common.CustomerNotFoundException
import ai.rojan.backend.domain.common.CustomerTagNotFoundException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.customer.CustomerActivity
import ai.rojan.backend.domain.customer.CustomerActivityRepository
import ai.rojan.backend.domain.customer.CustomerActivityType
import ai.rojan.backend.domain.customer.CustomerId
import ai.rojan.backend.domain.customer.CustomerRepository
import ai.rojan.backend.domain.customer.CustomerTagId
import ai.rojan.backend.domain.customer.CustomerTagRepository
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId

data class RemoveCustomerTagCommand(val customerId: CustomerId, val tagId: CustomerTagId, val callerId: UserId)

class RemoveCustomerTagUseCase(
    private val salonRepository: SalonRepository,
    private val customerRepository: CustomerRepository,
    private val customerTagRepository: CustomerTagRepository,
    private val customerActivityRepository: CustomerActivityRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: RemoveCustomerTagCommand) {
        val customer = customerRepository.findById(command.customerId)
            ?: throw CustomerNotFoundException(command.customerId.value.toString())
        val salon = salonRepository.findById(customer.salonId)
            ?: throw SalonNotFoundException(customer.salonId.value.toString())
        salonPermissionResolver.require(salon.id, command.callerId, Permission.MANAGE_CRM)

        val tag = customerTagRepository.findById(command.tagId)
            ?.takeIf { it.customerId == customer.id }
            ?: throw CustomerTagNotFoundException(command.tagId.value.toString())

        customerTagRepository.deleteById(tag.id)
        customerActivityRepository.save(
            CustomerActivity.create(customer.id, CustomerActivityType.TAG_REMOVED, "Tag removed: ${tag.label}"),
        )
    }
}
