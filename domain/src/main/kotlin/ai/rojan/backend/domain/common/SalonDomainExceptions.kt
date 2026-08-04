package ai.rojan.backend.domain.common

class SalonNotFoundException(identifier: String) :
    DomainException("Salon not found: $identifier")

class SalonAccessDeniedException(salonId: String) :
    DomainException("You do not have permission to manage salon: $salonId")

/** Thrown when a salon must be resolved implicitly from the caller's identity but they own more than one. */
class AmbiguousSalonContextException(ownerId: String) :
    DomainException("Owner $ownerId has multiple salons; salon context cannot be resolved implicitly")

class BranchNotFoundException(identifier: String) :
    DomainException("Branch not found: $identifier")

class ServiceCategoryNotFoundException(identifier: String) :
    DomainException("Service category not found: $identifier")

class ServiceNotFoundException(identifier: String) :
    DomainException("Service not found: $identifier")

class SpecialistNotFoundException(identifier: String) :
    DomainException("Specialist not found: $identifier")
