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
