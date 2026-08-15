package ai.rojan.backend.domain.common

class WorkingHoursNotFoundException(identifier: String) :
    DomainException("Working hours not found: $identifier")

class WeeklyAvailabilityNotFoundException(identifier: String) :
    DomainException("Specialist weekly availability not found: $identifier")

class ScheduleOverrideNotFoundException(identifier: String) :
    DomainException("Schedule override not found: $identifier")

class SpecialistLeaveNotFoundException(identifier: String) :
    DomainException("Specialist leave not found: $identifier")

class SpecialistBlockNotFoundException(identifier: String) :
    DomainException("Specialist block not found: $identifier")

class BookingNotFoundException(identifier: String) :
    DomainException("Booking not found: $identifier")

class BookingConflictException(specialistId: String, start: String, end: String) :
    DomainException("Specialist $specialistId already has a booking overlapping $start - $end")

class InvalidBookingStateException(message: String) : DomainException(message)

class BookingAccessDeniedException(bookingId: String) :
    DomainException("You do not have permission to manage booking: $bookingId")

class SpecialistNotEligibleForServiceException(specialistId: String, serviceId: String) :
    DomainException("Specialist $specialistId is not eligible to perform service $serviceId")
