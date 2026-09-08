package ai.rojan.backend.application.customer

import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.common.UserNotFoundException
import ai.rojan.backend.domain.customer.Customer
import ai.rojan.backend.domain.customer.CustomerRepository
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository

data class ResolveOrCreateSalonCustomerCommand(
    val salonId: SalonId,
    val userId: UserId,
)

/**
 * BACKEND-CRM-CUSTOMER-IDENTITY-001 (Phase 1). Returns the salon's CRM
 * record ([Customer]) for a given account, creating a linked one from the
 * account's profile if the salon doesn't have one yet. This is how a
 * booking becomes attached to a first-class [Customer] without the salon
 * owner having to add every customer by hand first.
 *
 * Salon-scoped by construction: the lookup key is `(salonId, userId)` and a
 * create always stamps `salonId` from the resolved [ai.rojan.backend.domain.salon.Salon],
 * never from anything caller-controlled. One account maps to one [Customer]
 * per salon (many salons -> many isolated records for the same person),
 * enforced here and by the `uq_customers_salon_user` partial unique index.
 *
 * Idempotent: calling it again for the same `(salonId, userId)` returns the
 * same record, creating nothing.
 */
class ResolveOrCreateSalonCustomerUseCase(
    private val salonRepository: SalonRepository,
    private val customerRepository: CustomerRepository,
    private val userRepository: UserRepository,
) {
    fun execute(command: ResolveOrCreateSalonCustomerCommand): Customer {
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())

        customerRepository.findBySalonIdAndUserId(salon.id, command.userId)?.let { return it }

        val user = userRepository.findById(command.userId)
            ?: throw UserNotFoundException(command.userId.value.toString())

        // User carries no phone number; email is always present, which
        // satisfies Customer's "phone or email" invariant.
        val customer = Customer.create(
            salonId = salon.id,
            userId = user.id,
            fullName = user.fullName,
            phoneNumber = null,
            email = user.email,
            company = null,
        )
        return customerRepository.save(customer)
    }
}
