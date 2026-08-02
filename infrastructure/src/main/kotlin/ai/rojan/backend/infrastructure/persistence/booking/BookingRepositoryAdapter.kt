package ai.rojan.backend.infrastructure.persistence.booking

import ai.rojan.backend.domain.booking.Booking
import ai.rojan.backend.domain.booking.BookingId
import ai.rojan.backend.domain.booking.BookingRepository
import ai.rojan.backend.domain.booking.BookingStatus
import ai.rojan.backend.domain.common.BookingConflictException
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.ServiceId
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.user.UserId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDateTime

private val ACTIVE_STATUSES = listOf(BookingStatus.PENDING, BookingStatus.CONFIRMED)

/**
 * Repository-pattern adapter for [BookingRepository]. [reserve] is the sole
 * write path that can create or move a booking in time: it takes a
 * Postgres transaction-scoped advisory lock keyed by the specialist before
 * checking for overlaps, so concurrent requests for the same specialist are
 * serialized and the overlap check + insert is effectively atomic — no
 * external DB extension (e.g. btree_gist exclusion constraints) required.
 */
@Repository
class BookingRepositoryAdapter(
    private val jpaRepository: BookingSpringDataRepository,
    private val jdbcTemplate: JdbcTemplate,
) : BookingRepository {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun reserve(booking: Booking, excludeBookingId: BookingId?): Booking {
        val lockKey = booking.specialistId.value.leastSignificantBits
        jdbcTemplate.execute("SELECT pg_advisory_xact_lock($lockKey)")

        val overlapping = jpaRepository.findOverlapping(
            specialistId = booking.specialistId.value,
            startTime = booking.startTime,
            endTime = booking.endTime,
            excludeId = excludeBookingId?.value,
            statuses = ACTIVE_STATUSES,
        )
        if (overlapping.isNotEmpty()) {
            throw BookingConflictException(
                booking.specialistId.value.toString(),
                booking.startTime.toString(),
                booking.endTime.toString(),
            )
        }
        return persist(booking)
    }

    override fun save(booking: Booking): Booking = persist(booking)

    override fun findById(id: BookingId): Booking? =
        jpaRepository.findById(id.value).orElse(null)?.toDomain()

    override fun findBySalonId(salonId: SalonId): List<Booking> =
        jpaRepository.findBySalonId(salonId.value).map { it.toDomain() }

    override fun findByCustomerId(customerId: UserId): List<Booking> =
        jpaRepository.findByCustomerId(customerId.value).map { it.toDomain() }

    override fun findActiveBySpecialistIdAndDateRange(
        specialistId: SpecialistId,
        from: LocalDateTime,
        to: LocalDateTime,
    ): List<Booking> = jpaRepository.findActiveBySpecialistIdAndDateRange(specialistId.value, from, to, ACTIVE_STATUSES)
        .map { it.toDomain() }

    private fun persist(booking: Booking): Booking {
        val entity = jpaRepository.findById(booking.id.value).orElse(null)
            ?.apply {
                startTime = booking.startTime
                endTime = booking.endTime
                status = booking.status
                notes = booking.notes
            }
            ?: BookingJpaEntity(
                id = booking.id.value,
                salonId = booking.salonId.value,
                serviceId = booking.serviceId.value,
                specialistId = booking.specialistId.value,
                customerId = booking.customerId.value,
                startTime = booking.startTime,
                endTime = booking.endTime,
                status = booking.status,
                notes = booking.notes,
            )
        return jpaRepository.save(entity).toDomain()
    }

    private fun BookingJpaEntity.toDomain(): Booking = Booking.reconstitute(
        id = BookingId(id),
        salonId = SalonId(salonId),
        serviceId = ServiceId(serviceId),
        specialistId = SpecialistId(specialistId),
        customerId = UserId(customerId),
        startTime = startTime,
        endTime = endTime,
        status = status,
        notes = notes,
        createdAt = createdAt ?: Instant.EPOCH,
        updatedAt = updatedAt ?: Instant.EPOCH,
    )
}
