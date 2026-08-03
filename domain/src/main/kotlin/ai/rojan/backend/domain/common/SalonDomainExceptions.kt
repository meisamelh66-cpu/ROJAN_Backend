package ai.rojan.backend.domain.common

class SalonNotFoundException(identifier: String) :
    DomainException("Salon not found: $identifier")

class SalonAccessDeniedException(salonId: String) :
    DomainException("You do not have permission to manage salon: $salonId")

class BranchNotFoundException(identifier: String) :
    DomainException("Branch not found: $identifier")

class ServiceCategoryNotFoundException(identifier: String) :
    DomainException("Service category not found: $identifier")

class ServiceNotFoundException(identifier: String) :
    DomainException("Service not found: $identifier")

class SpecialistNotFoundException(identifier: String) :
    DomainException("Specialist not found: $identifier")
