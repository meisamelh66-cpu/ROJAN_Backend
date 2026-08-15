package ai.rojan.backend.domain.salon

import ai.rojan.backend.domain.user.UserId
import java.time.Duration
import java.time.Instant
import java.util.UUID

@JvmInline
value class SalonInviteId(val value: UUID) {
    companion object {
        fun new(): SalonInviteId = SalonInviteId(UUID.randomUUID())
    }
}

/**
 * A single-use, expiring token granting [role] at [salonId] to whoever
 * accepts it - the mechanism that replaces "the owner must already know the
 * target's account id" for [SalonMembership] assignment (see
 * `AssignMembershipUseCase`'s own doc comment on that prior gap). The token
 * itself is this invite's authorization: unlike a direct membership
 * assignment, the accepting caller holds no salon permission yet - proving
 * possession of a still-[SalonInviteStatus.CREATED], unexpired token is
 * what stands in for it.
 */
class SalonInvite private constructor(
    val id: SalonInviteId,
    val salonId: SalonId,
    val role: SalonRole,
    val token: String,
    status: SalonInviteStatus,
    val expiresAt: Instant,
    val createdBy: UserId,
    acceptedBy: UserId?,
    val createdAt: Instant,
    updatedAt: Instant,
) {
    var status: SalonInviteStatus = status
        private set

    var acceptedBy: UserId? = acceptedBy
        private set

    var updatedAt: Instant = updatedAt
        private set

    /** The stored [status] alone can be stale by design (see [SalonInviteStatus]'s own doc comment) - every read site should call this instead. */
    fun currentStatus(now: Instant = Instant.now()): SalonInviteStatus =
        if (status == SalonInviteStatus.CREATED && expiresAt.isBefore(now)) SalonInviteStatus.EXPIRED else status

    /**
     * Mutates this in-memory instance only - defensive `check()`, not a
     * user-facing validation, because the real gate is
     * [SalonInviteRepository.acceptIfAvailable]'s atomic re-check at the
     * database layer (mirroring `BookingRepository.reserve()`'s identical
     * check-inside-lock pattern for the same class of concurrent-race
     * problem). By the time this is called, that atomic check has already
     * succeeded.
     */
    fun accept(userId: UserId, now: Instant = Instant.now()) {
        check(currentStatus(now) == SalonInviteStatus.CREATED) {
            "Cannot accept invite ${id.value} in status ${currentStatus(now)}"
        }
        status = SalonInviteStatus.ACCEPTED
        acceptedBy = userId
        updatedAt = now
    }

    /** Revoking isn't subject to the same concurrent-race severity as [accept] (a lost revoke-revoke race still ends REVOKED either way) - a plain read-check-write from the use case is sufficient, no atomic repository method needed. */
    fun revoke(now: Instant = Instant.now()) {
        check(currentStatus(now) == SalonInviteStatus.CREATED) {
            "Cannot revoke invite ${id.value} in status ${currentStatus(now)}"
        }
        status = SalonInviteStatus.REVOKED
        updatedAt = now
    }

    companion object {
        fun create(salonId: SalonId, role: SalonRole, createdBy: UserId, ttl: Duration): SalonInvite {
            val now = Instant.now()
            return SalonInvite(
                id = SalonInviteId.new(),
                salonId = salonId,
                role = role,
                token = SalonInviteTokenGenerator.generate(),
                status = SalonInviteStatus.CREATED,
                expiresAt = now.plus(ttl),
                createdBy = createdBy,
                acceptedBy = null,
                createdAt = now,
                updatedAt = now,
            )
        }

        fun reconstitute(
            id: SalonInviteId,
            salonId: SalonId,
            role: SalonRole,
            token: String,
            status: SalonInviteStatus,
            expiresAt: Instant,
            createdBy: UserId,
            acceptedBy: UserId?,
            createdAt: Instant,
            updatedAt: Instant,
        ): SalonInvite = SalonInvite(id, salonId, role, token, status, expiresAt, createdBy, acceptedBy, createdAt, updatedAt)
    }
}

interface SalonInviteRepository {
    fun save(invite: SalonInvite): SalonInvite
    fun findById(id: SalonInviteId): SalonInvite?
    fun findByToken(token: String): SalonInvite?
    fun findBySalonId(salonId: SalonId): List<SalonInvite>

    /**
     * Atomically transitions [token]'s invite CREATED -> ACCEPTED iff it is
     * still CREATED and unexpired at the moment of the database write
     * (re-checked inside the same lock, not just trusted from an earlier
     * read) - returns `null`, not an exception, if the transition couldn't
     * happen, mirroring `BookingRepository.reserve()`'s check-inside-lock
     * pattern for the identical class of concurrent-accept race. This is
     * the single-use guarantee's actual enforcement point.
     */
    fun acceptIfAvailable(token: String, acceptedBy: UserId, now: Instant): SalonInvite?
}
