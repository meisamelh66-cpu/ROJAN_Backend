package ai.rojan.backend.domain.common

class CustomerNotFoundException(identifier: String) :
    DomainException("Customer not found: $identifier")

class CustomerAccessDeniedException(customerId: String) :
    DomainException("You do not have permission to manage customer: $customerId")

class InvalidCustomerStateException(message: String) : DomainException(message)

/**
 * A customer record that would collide with an existing one in the same
 * salon - either the same phone number
 * (`CustomerRepository.existsBySalonIdAndPhoneNumber`) or the same linked
 * account (`CustomerRepository.findBySalonIdAndUserId`, guarded by the
 * `uq_customers_salon_user` partial unique index). Maps to HTTP 409.
 */
class CustomerAlreadyExistsException(message: String) : DomainException(message) {
    companion object {
        fun forPhoneNumber(phoneNumber: String) =
            CustomerAlreadyExistsException("A customer with phone number $phoneNumber already exists for this salon")

        fun forLinkedAccount(userId: String) =
            CustomerAlreadyExistsException("This salon already has a customer record linked to account $userId")
    }
}

class CustomerTagNotFoundException(identifier: String) :
    DomainException("Customer tag not found: $identifier")

/** Thrown by `CreateBookingForCustomerUseCase` when the owner/reception-selected customer has no linked `User` account yet - `Booking.customerId` is a real `UserId`, so there is nothing to attribute the booking to until the customer is linked. See `ROJAN_Reception_Booking_Flow_Plan_v1.md` §4/§7 for why full walk-in (unlinked) booking support is a separate, larger decision, not attempted here. */
class CustomerNotLinkedToAccountException(customerId: String) :
    DomainException("Customer $customerId has no linked account yet and cannot be booked through this path")
