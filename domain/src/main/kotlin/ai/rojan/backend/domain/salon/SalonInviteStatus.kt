package ai.rojan.backend.domain.salon

/**
 * [EXPIRED] is never written back to a [SalonInvite] row by a background
 * job - it's a status the stored [CREATED] value can *become*, computed at
 * read time via [SalonInvite.currentStatus]. Every read site must go
 * through that method rather than comparing the raw stored value.
 */
enum class SalonInviteStatus {
    CREATED,
    ACCEPTED,
    EXPIRED,
    REVOKED,
}
