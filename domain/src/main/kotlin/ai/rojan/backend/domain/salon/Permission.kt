package ai.rojan.backend.domain.salon

/**
 * The full set of salon-scoped actions this codebase's authorization can
 * grant. Resolved per-caller by `application.salon.SalonPermissionResolver`
 * from three independent sources - [Salon.ownerId] equality (implicit,
 * every permission), a [SalonMembership] row ([SalonRole.permissions]), or
 * an own [Specialist] link (implicit [MANAGE_SCHEDULE_OWN], scoped to that
 * one record) - never stored directly on a `User`.
 */
enum class Permission {
    MANAGE_SALON,
    MANAGE_MEMBERSHIP,
    MANAGE_CATALOG,
    MANAGE_STAFF,
    MANAGE_SCHEDULE_ALL,
    MANAGE_SCHEDULE_OWN,
    VIEW_CRM,
    MANAGE_CRM,
    MANAGE_BOOKINGS,
    MANAGE_OWN_BOOKINGS,
    MANAGE_MEDIA,
}
