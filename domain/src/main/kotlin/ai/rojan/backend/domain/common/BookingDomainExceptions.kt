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

/** Self-service booking creation (`POST /api/v1/bookings`) is CUSTOMER-role only - MANAGER/SPECIALIST accounts create bookings for a customer through the salon-scoped staff endpoint instead, which is authorized separately via [ai.rojan.backend.domain.salon.Permission.MANAGE_BOOKINGS]. */
class BookingRoleNotAllowedException(role: String) :
    DomainException("Role $role is not permitted to create a self-service booking")

class SpecialistNotEligibleForServiceException(specialistId: String, serviceId: String) :
    DomainException("Specialist $specialistId is not eligible to perform service $serviceId")
