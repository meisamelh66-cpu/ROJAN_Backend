package ai.rojan.backend.domain.common

class CustomerNotFoundException(identifier: String) :
    DomainException("Customer not found: $identifier")

class CustomerAccessDeniedException(customerId: String) :
    DomainException("You do not have permission to manage customer: $customerId")

class InvalidCustomerStateException(message: String) : DomainException(message)

/** A customer with this phone number already exists within this salon (see `CustomerRepository.existsBySalonIdAndPhoneNumber`). */
class CustomerAlreadyExistsException(phoneNumber: String) :
    DomainException("A customer with phone number $phoneNumber already exists for this salon")

class CustomerTagNotFoundException(identifier: String) :
    DomainException("Customer tag not found: $identifier")

/** Thrown by `CreateBookingForCustomerUseCase` when the owner/reception-selected customer has no linked `User` account yet - `Booking.customerId` is a real `UserId`, so there is nothing to attribute the booking to until the customer is linked. See `ROJAN_Reception_Booking_Flow_Plan_v1.md` §4/§7 for why full walk-in (unlinked) booking support is a separate, larger decision, not attempted here. */
class CustomerNotLinkedToAccountException(customerId: String) :
    DomainException("Customer $customerId has no linked account yet and cannot be booked through this path")

/** Thrown by `LinkCustomerToUserUseCase` when the target Customer already has a linked account - reconciliation is explicit and one-shot, never a silent overwrite (see `ROJAN_Customer_CRM_Architecture_Plan_v1.md` §6.4). */
class CustomerAlreadyLinkedException(customerId: String) :
    DomainException("Customer $customerId is already linked to an account")

/** Thrown by `LinkCustomerToUserUseCase` when the target User is already linked to a different Customer within the same salon - would otherwise violate `uq_customers_salon_user`. */
class UserAlreadyLinkedToCustomerException(userId: String, salonId: String) :
    DomainException("User $userId is already linked to another customer in salon $salonId")
