package ai.rojan.backend.infrastructure.persistence.salon

import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonInvite
import ai.rojan.backend.domain.salon.SalonInviteId
import ai.rojan.backend.domain.salon.SalonInviteRepository
import ai.rojan.backend.domain.salon.SalonInviteStatus
import ai.rojan.backend.domain.user.UserId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Repository-pattern adapter for [SalonInviteRepository]. [acceptIfAvailable]
 * is the sole write path that can transition CREATED -> ACCEPTED: it takes a
 * Postgres transaction-scoped advisory lock keyed by the invite's id before
 * re-checking its status, so concurrent accept attempts for the same token
 * are serialized and the check + write is effectively atomic - the same
 * technique [ai.rojan.backend.infrastructure.persistence.booking.BookingRepositoryAdapter.reserve]
 * already uses for the identical class of concurrent-write race.
 */
@Repository
class SalonInviteRepositoryAdapter(
    private val jpaRepository: SalonInviteSpringDataRepository,
    private val jdbcTemplate: JdbcTemplate,
) : SalonInviteRepository {

    override fun save(invite: SalonInvite): SalonInvite {
        val entity = jpaRepository.findById(invite.id.value).orElse(null)
            ?.apply {
                status = invite.status
                acceptedBy = invite.acceptedBy?.value
            }
            ?: SalonInviteJpaEntity(
                id = invite.id.value,
                salonId = invite.salonId.value,
                role = invite.role,
                token = invite.token,
                status = invite.status,
                expiresAt = invite.expiresAt,
                createdBy = invite.createdBy.value,
                acceptedBy = invite.acceptedBy?.value,
            )
        return jpaRepository.save(entity).toDomain()
    }

    override fun findById(id: SalonInviteId): SalonInvite? =
        jpaRepository.findById(id.value).orElse(null)?.toDomain()

    override fun findByToken(token: String): SalonInvite? =
        jpaRepository.findByToken(token)?.toDomain()

    override fun findBySalonId(salonId: SalonId): List<SalonInvite> =
        jpaRepository.findBySalonId(salonId.value).map { it.toDomain() }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun acceptIfAvailable(token: String, acceptedBy: UserId, now: Instant): SalonInvite? {
        val existing = jpaRepository.findByToken(token) ?: return null
        val lockKey = existing.id.leastSignificantBits
        jdbcTemplate.execute("SELECT pg_advisory_xact_lock($lockKey)")

        val current = jpaRepository.findById(existing.id).orElse(null) ?: return null
        if (current.toDomain().currentStatus(now) != SalonInviteStatus.CREATED) return null

        current.status = SalonInviteStatus.ACCEPTED
        current.acceptedBy = acceptedBy.value
        return jpaRepository.save(current).toDomain()
    }

    private fun SalonInviteJpaEntity.toDomain(): SalonInvite = SalonInvite.reconstitute(
        id = SalonInviteId(id),
        salonId = SalonId(salonId),
        role = role,
        token = token,
        status = status,
        expiresAt = expiresAt,
        createdBy = UserId(createdBy),
        acceptedBy = acceptedBy?.let { UserId(it) },
        createdAt = createdAt ?: Instant.EPOCH,
        updatedAt = updatedAt ?: Instant.EPOCH,
    )
}
