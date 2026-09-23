package ai.rojan.backend.domain.common

/**
 * Thrown whenever a caller lacks [ai.rojan.backend.domain.user.UserRole.PLATFORM_REVIEWER]/
 * [ai.rojan.backend.domain.user.UserRole.PLATFORM_ADMIN] authority for a
 * platform-scoped action - deliberately separate from
 * [SalonAccessDeniedException] (salon-scoped authority, resolved via
 * [ai.rojan.backend.domain.salon.SalonMembership]/ownership). Platform
 * authority is never salon-scoped and must never be resolved through that
 * path - see [ai.rojan.backend.application.platformauthority.PlatformAuthorizationResolver]'s
 * own doc comment.
 */
class PlatformAccessDeniedException(callerId: String) :
    DomainException("Caller does not have platform reviewer/admin authority: $callerId")
