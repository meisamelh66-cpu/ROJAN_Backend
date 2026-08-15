package ai.rojan.backend.application.salon

import ai.rojan.backend.application.port.RateLimiterPort
import ai.rojan.backend.domain.common.SalonInviteAcceptRateLimitExceededException
import ai.rojan.backend.domain.common.SalonInviteNotFoundException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonInvite
import ai.rojan.backend.domain.salon.SalonInviteId
import ai.rojan.backend.domain.salon.SalonInviteRepository
import ai.rojan.backend.domain.salon.SalonInviteStatus
import ai.rojan.backend.domain.salon.SalonMembershipRepository
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.SalonRole
import ai.rojan.backend.domain.user.UserId
import java.time.Duration
import java.time.Instant

/** [ttl] is an internal override for tests only - never exposed on [ai.rojan.backend.api.salon.CreateSalonInviteRequest], so a real caller can never mint a longer- or shorter-lived invite than this use case's own configured default. */
data class CreateSalonInviteCommand(val salonId: SalonId, val callerId: UserId, val role: SalonRole, val ttl: Duration? = null)

/**
 * Owner-only ([Permission.MANAGE_MEMBERSHIP] - never granted to
 * [SalonRole.MANAGER], see that enum's own doc comment on the
 * privilege-escalation chain this already closes). Because that permission
 * is exclusively the owner's today, this one check is sufficient for both
 * `role=MANAGER` and `role=RECEPTIONIST` invites - no extra role-specific
 * branching needed to prevent a manager from inviting another manager.
 */
class CreateSalonInviteUseCase(
    private val salonRepository: SalonRepository,
    private val salonInviteRepository: SalonInviteRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
    private val defaultTtl: Duration = Duration.ofHours(48),
) {
    fun execute(command: CreateSalonInviteCommand): SalonInvite {
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        salonPermissionResolver.require(salon.id, command.callerId, Permission.MANAGE_MEMBERSHIP)

        val invite = SalonInvite.create(salon.id, command.role, command.callerId, command.ttl ?: defaultTtl)
        return salonInviteRepository.save(invite)
    }
}

data class ListSalonInvitesCommand(val salonId: SalonId, val callerId: UserId)

class ListSalonInvitesUseCase(
    private val salonRepository: SalonRepository,
    private val salonInviteRepository: SalonInviteRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: ListSalonInvitesCommand): List<SalonInvite> {
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        salonPermissionResolver.require(salon.id, command.callerId, Permission.MANAGE_MEMBERSHIP)

        return salonInviteRepository.findBySalonId(salon.id)
    }
}

data class RevokeSalonInviteCommand(val salonId: SalonId, val inviteId: SalonInviteId, val callerId: UserId)

class RevokeSalonInviteUseCase(
    private val salonRepository: SalonRepository,
    private val salonInviteRepository: SalonInviteRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: RevokeSalonInviteCommand) {
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        salonPermissionResolver.require(salon.id, command.callerId, Permission.MANAGE_MEMBERSHIP)

        val invite = salonInviteRepository.findById(command.inviteId)
            ?.takeIf { it.salonId == salon.id && it.currentStatus() == SalonInviteStatus.CREATED }
            ?: throw SalonInviteNotFoundException(command.inviteId.value.toString())

        invite.revoke()
        salonInviteRepository.save(invite)
    }
}

data class GetSalonInviteCommand(val token: String)

data class SalonInviteDetails(val salonName: String, val role: SalonRole)

/**
 * Unauthenticated by design - the confirmation screen a QR scan lands on
 * before the staff member logs in. [SalonInviteNotFoundException] covers
 * "never existed", "expired", "revoked", and "already accepted" uniformly
 * here on purpose (see that exception's own doc comment) - an anonymous
 * caller must never be able to distinguish those cases from the response
 * shape, the same principle `PublicSalonController`'s DRAFT-salon-404
 * already established for this codebase.
 */
class GetSalonInviteUseCase(
    private val salonRepository: SalonRepository,
    private val salonInviteRepository: SalonInviteRepository,
) {
    fun execute(command: GetSalonInviteCommand): SalonInviteDetails {
        val invite = salonInviteRepository.findByToken(command.token)
            ?.takeIf { it.currentStatus() == SalonInviteStatus.CREATED }
            ?: throw SalonInviteNotFoundException(command.token)
        val salon = salonRepository.findById(invite.salonId)
            ?: throw SalonInviteNotFoundException(command.token)
        return SalonInviteDetails(salon.name, invite.role)
    }
}

data class AcceptSalonInviteCommand(val token: String, val callerId: UserId)

/**
 * [AssignMembershipUseCase] is deliberately *not* reused here even though
 * both ultimately write a [ai.rojan.backend.domain.salon.SalonMembership]
 * row - its [Permission.MANAGE_MEMBERSHIP] check assumes the caller already
 * holds authority at the salon, which the accepting staff member never does
 * (they're gaining authority, not exercising it). A [SalonInvite] token -
 * already proven [SalonInviteStatus.CREATED], unexpired, and consumed
 * exactly once by [SalonInviteRepository.acceptIfAvailable] before this
 * point - is this path's authorization instead.
 * [SalonMembershipRepository.assign] remains the one place a membership row
 * is ever written; both use cases call the same repository method, so
 * there's no second, parallel write path to drift out of sync.
 */
class AcceptSalonInviteUseCase(
    private val salonInviteRepository: SalonInviteRepository,
    private val membershipRepository: SalonMembershipRepository,
    private val rateLimiter: RateLimiterPort,
) {
    fun execute(command: AcceptSalonInviteCommand): SalonInvite {
        enforceAcceptRateLimit(command.token)

        val accepted = salonInviteRepository.acceptIfAvailable(command.token, command.callerId, Instant.now())
            ?: throw SalonInviteNotFoundException(command.token)

        membershipRepository.assign(accepted.salonId, command.callerId, accepted.role)
        return accepted
    }

    /** Guards against rapid guessing of a still-unknown token - distinct from [SalonInviteRepository.acceptIfAvailable]'s single-use guarantee, which only protects an already-known, already-valid token from being consumed twice. */
    private fun enforceAcceptRateLimit(token: String) {
        val key = "invite:accept:token:$token"
        if (!rateLimiter.tryConsume(key, ACCEPT_ATTEMPT_LIMIT, ACCEPT_ATTEMPT_WINDOW)) {
            throw SalonInviteAcceptRateLimitExceededException(token)
        }
    }

    private companion object {
        const val ACCEPT_ATTEMPT_LIMIT = 10
        val ACCEPT_ATTEMPT_WINDOW: Duration = Duration.ofMinutes(15)
    }
}
