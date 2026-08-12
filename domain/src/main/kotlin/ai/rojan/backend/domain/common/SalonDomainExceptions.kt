package ai.rojan.backend.domain.common

class SalonNotFoundException(identifier: String) :
    DomainException("Salon not found: $identifier")

class SalonAccessDeniedException(salonId: String) :
    DomainException("You do not have permission to manage salon: $salonId")

class SalonSlugAlreadyTakenException(slug: String) :
    DomainException("Salon slug is already taken: $slug")

class SalonNotReadyForActivationException(salonId: String, missingRequirements: List<String>) :
    DomainException("Salon $salonId is not ready for activation, missing: ${missingRequirements.joinToString(", ")}")

/** Thrown by [ai.rojan.backend.domain.salon.Salon.requireActivated] - a DRAFT salon may be fully managed by its owner but cannot transact (bookings, CRM writes) until activated. */
class SalonNotActiveException(salonId: String) :
    DomainException("Salon $salonId is not active yet - complete setup and activate it before booking or customer operations")

class InvalidMembershipAssignmentException(message: String) : DomainException(message)

/**
 * Covers "doesn't exist", "wrong salon", and "exists but not currently
 * usable" (expired/revoked/already-accepted) uniformly - same precedent as
 * [ServiceNotFoundException] already combining not-found and inactive into
 * one exception. For the anonymous token-lookup/accept paths this is
 * deliberate: an invalid, expired, and revoked token must all look
 * identical to the caller, so there is nothing to distinguish by throwing a
 * more specific type.
 */
class SalonInviteNotFoundException(identifier: String) :
    DomainException("Salon invite not found or no longer available: $identifier")

/** Guards `POST /invites/{token}/accept` against rapid guessing of a still-unknown token, same reasoning as [ai.rojan.backend.domain.common.OtpVerifyRateLimitExceededException]. */
class SalonInviteAcceptRateLimitExceededException(token: String) :
    DomainException("Too many accept attempts for invite token: $token")

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
