package ai.rojan.backend.domain.booking

import ai.rojan.backend.domain.common.InvalidBookingStateException
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.ServiceId
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.user.UserId
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

enum class BookingStatus {
    PENDING,
    CONFIRMED,
    CHECKED_IN,
    CANCELLED,
    COMPLETED,
}

@JvmInline
value class BookingId(val value: UUID) {
    companion object {
        fun new(): BookingId = BookingId(UUID.randomUUID())
    }
}

/**
 * A customer's reservation of a specialist's time for a service. Created as
 * [BookingStatus.PENDING] and only becomes a hard reservation once
 * [BookingStatus.CONFIRMED] by the salon; conflict-freedom is enforced by
 * the persistence layer (see [BookingRepository.reserve]), not here — this
 * aggregate only guards its own state-transition invariants.
 */
class Booking private constructor(
    val id: BookingId,
    val salonId: SalonId,
    val serviceId: ServiceId,
    val specialistId: SpecialistId,
    val customerId: UserId,
    startTime: LocalDateTime,
    endTime: LocalDateTime,
    status: BookingStatus,
    notes: String?,
    val createdAt: Instant,
    updatedAt: Instant,
) {
    var startTime: LocalDateTime = startTime
        private set

    var endTime: LocalDateTime = endTime
        private set

    var status: BookingStatus = status
        private set

    var notes: String? = notes
        private set

    var updatedAt: Instant = updatedAt
        private set

    fun confirm() {
        if (status != BookingStatus.PENDING) {
            throw InvalidBookingStateException("Only a pending booking can be confirmed (current status: $status)")
        }
        status = BookingStatus.CONFIRMED
        updatedAt = Instant.now()
    }

    fun cancel() {
        if (status != BookingStatus.PENDING && status != BookingStatus.CONFIRMED) {
            throw InvalidBookingStateException("Only a pending or confirmed booking can be cancelled (current status: $status)")
        }
        status = BookingStatus.CANCELLED
        updatedAt = Instant.now()
    }

    /**
     * Reception Check-in (Phase 4): an optional intermediate step between [confirm] and
     * [complete] - strictly `CONFIRMED -> CHECKED_IN` only, same strict-transition style as every
     * other method here (no idempotent re-check-in, no check-in from [BookingStatus.PENDING]/
     * [BookingStatus.CANCELLED]/[BookingStatus.COMPLETED]). Deliberately optional, not mandatory:
     * [complete] still accepts a booking straight from [BookingStatus.CONFIRMED] (see below) so
     * salons with no Reception/check-in step of their own are completely unaffected.
     */
    fun checkIn() {
        if (status != BookingStatus.CONFIRMED) {
            throw InvalidBookingStateException("Only a confirmed booking can be checked in (current status: $status)")
        }
        status = BookingStatus.CHECKED_IN
        updatedAt = Instant.now()
    }

    /**
     * Reception Check-in (Phase 4): widened to accept either [BookingStatus.CONFIRMED] (check-in
     * skipped - every salon's existing behavior, unchanged) or [BookingStatus.CHECKED_IN] (the new,
     * optional path) - never from [BookingStatus.PENDING]/[BookingStatus.CANCELLED]/already
     * [BookingStatus.COMPLETED].
     */
    fun complete() {
        if (status != BookingStatus.CONFIRMED && status != BookingStatus.CHECKED_IN) {
            throw InvalidBookingStateException("Only a confirmed or checked-in booking can be completed (current status: $status)")
        }
        status = BookingStatus.COMPLETED
        updatedAt = Instant.now()
    }

    fun reschedule(newStartTime: LocalDateTime, newEndTime: LocalDateTime) {
        if (status != BookingStatus.PENDING && status != BookingStatus.CONFIRMED) {
            throw InvalidBookingStateException("Only a pending or confirmed booking can be rescheduled (current status: $status)")
        }
        require(newStartTime < newEndTime) { "Booking start must be before end" }
        startTime = newStartTime
        endTime = newEndTime
        updatedAt = Instant.now()
    }

    val isActive: Boolean get() = status == BookingStatus.PENDING || status == BookingStatus.CONFIRMED || status == BookingStatus.CHECKED_IN

    companion object {
        fun create(
            salonId: SalonId,
            serviceId: ServiceId,
            specialistId: SpecialistId,
            customerId: UserId,
            startTime: LocalDateTime,
            endTime: LocalDateTime,
            notes: String?,
        ): Booking {
            require(startTime < endTime) { "Booking start must be before end" }
            val now = Instant.now()
            return Booking(
                id = BookingId.new(),
                salonId = salonId,
                serviceId = serviceId,
                specialistId = specialistId,
                customerId = customerId,
                startTime = startTime,
                endTime = endTime,
                status = BookingStatus.PENDING,
                notes = notes?.trim()?.ifBlank { null },
                createdAt = now,
                updatedAt = now,
            )
        }

        fun reconstitute(
            id: BookingId,
            salonId: SalonId,
            serviceId: ServiceId,
            specialistId: SpecialistId,
            customerId: UserId,
            startTime: LocalDateTime,
            endTime: LocalDateTime,
            status: BookingStatus,
            notes: String?,
            createdAt: Instant,
            updatedAt: Instant,
        ): Booking = Booking(
            id, salonId, serviceId, specialistId, customerId, startTime, endTime, status, notes, createdAt, updatedAt,
        )
    }
}
