package ai.rojan.backend.application.customer

import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.common.UserNotFoundException
import ai.rojan.backend.domain.customer.Customer
import ai.rojan.backend.domain.customer.CustomerRepository
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository

data class EnsureCustomerAssociationCommand(val salonId: SalonId, val userId: UserId)

/**
 * Find-or-create: a self-service booking (`BookingController.create`) never
 * used to leave any CRM trace of the customer at that salon - reception-
 * created bookings always went through a linked `Customer`, but the
 * customer QR/self-service journey had no equivalent. Called once, right
 * before booking creation, for every self-service booking - idempotent by
 * construction (find-first), and the concurrent-duplicate race that leaves
 * is closed at the database level by `uq_customers_salon_user`
 * (`V12__customers_unique_linked_user_per_salon.sql`), not here.
 *
 * [Salon.requireActivated] is enforced up front, before the find-or-create
 * branch - a DRAFT salon can never gain a CRM trace, whether or not a
 * `Customer` already exists for this `(salonId, userId)` pair.
 */
class EnsureCustomerAssociationUseCase(
    private val salonRepository: SalonRepository,
    private val customerRepository: CustomerRepository,
    private val userRepository: UserRepository,
) {
    fun execute(command: EnsureCustomerAssociationCommand): Customer {
        val salon = salonRepository.findById(command.salonId)?.takeIf { it.active }
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        salon.requireActivated()

        customerRepository.findBySalonIdAndUserId(command.salonId, command.userId)?.let { return it }

        val user = userRepository.findById(command.userId)
            ?: throw UserNotFoundException(command.userId.value.toString())

        val customer = Customer.create(
            salonId = command.salonId,
            userId = user.id,
            fullName = user.fullName,
            phoneNumber = user.phoneNumber,
            email = user.email,
            company = null,
        )
        return customerRepository.save(customer)
    }
}
