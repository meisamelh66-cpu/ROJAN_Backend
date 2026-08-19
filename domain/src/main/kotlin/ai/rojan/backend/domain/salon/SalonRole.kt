package ai.rojan.backend.domain.salon

/**
 * A [SalonMembership]'s role. `OWNER` is deliberately not a value here -
 * ownership is [Salon.ownerId], never a membership row (see that field's
 * doc comment and `SalonPermissionResolver`).
 */
enum class SalonRole {
    /**
     * Operational management - catalog, staff, schedules, CRM, bookings -
     * but never [Permission.MANAGE_SALON] or [Permission.MANAGE_MEMBERSHIP]:
     * a manager can never change salon settings or grant/revoke anyone
     * else's access, closing the obvious privilege-escalation chain
     * (manager invites another manager who invites another...).
     */
    MANAGER,

    /** Booking operations only - the front-desk role. */
    RECEPTIONIST,
    ;

    fun permissions(): Set<Permission> = when (this) {
        MANAGER -> setOf(
            Permission.MANAGE_CATALOG,
            Permission.MANAGE_STAFF,
            Permission.MANAGE_SCHEDULE_ALL,
            Permission.VIEW_CRM,
            Permission.MANAGE_CRM,
            Permission.MANAGE_BOOKINGS,
            Permission.VIEW_CUSTOMER_IDENTITY,
            Permission.CREATE_CUSTOMER_IDENTITY,
            Permission.VIEW_CUSTOMER_BOOKING_HISTORY,
        )
        // Reception Permission Contract Update ADR v1: booking-operational access plus the
        // three narrow customer-identity permissions - deliberately no VIEW_CRM/MANAGE_CRM.
        RECEPTIONIST -> setOf(
            Permission.MANAGE_BOOKINGS,
            Permission.VIEW_CUSTOMER_IDENTITY,
            Permission.CREATE_CUSTOMER_IDENTITY,
            Permission.VIEW_CUSTOMER_BOOKING_HISTORY,
        )
    }
}
