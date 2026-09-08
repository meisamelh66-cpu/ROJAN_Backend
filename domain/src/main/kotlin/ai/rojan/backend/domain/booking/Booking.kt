package ai.rojan.backend.domain.booking

import ai.rojan.backend.domain.common.InvalidBookingStateException
import ai.rojan.backend.domain.customer.CustomerId
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
 *
 * BACKEND-CRM-CUSTOMER-IDENTITY-001 (Phase 1, additive): [customerId] is
 * unchanged - it is still the account the booking is attributed to (self-app
 * queries, the double-booking conflict key, and caller-vs-owner access all
 * key on it). [salonCustomerId] is the new, nullable link to the salon's CRM
 * record ([ai.rojan.backend.domain.customer.Customer]). It is `null` only on
 * bookings created before this reference existed (until the one-time backfill
 * runs); every booking created through the use cases now carries it. Making
 * it non-null / the primary anchor is a later, migration-gated phase.
 */
class Booking private constructor(
    val id: BookingId,
    val salonId: SalonId,
    val serviceId: ServiceId,
    val specialistId: SpecialistId,
    val customerId: UserId,
    val salonCustomerId: CustomerId?,
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

    fun complete() {
        if (status != BookingStatus.CONFIRMED) {
            throw InvalidBookingStateException("Only a confirmed booking can be completed (current status: $status)")
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

    val isActive: Boolean get() = status == BookingStatus.PENDING || status == BookingStatus.CONFIRMED

    companion object {
        fun create(
            salonId: SalonId,
            serviceId: ServiceId,
            specialistId: SpecialistId,
            customerId: UserId,
            startTime: LocalDateTime,
            endTime: LocalDateTime,
            notes: String?,
            salonCustomerId: CustomerId? = null,
        ): Booking {
            require(startTime < endTime) { "Booking start must be before end" }
            val now = Instant.now()
            return Booking(
                id = BookingId.new(),
                salonId = salonId,
                serviceId = serviceId,
                specialistId = specialistId,
                customerId = customerId,
                salonCustomerId = salonCustomerId,
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
            salonCustomerId: CustomerId? = null,
        ): Booking = Booking(
            id, salonId, serviceId, specialistId, customerId, salonCustomerId,
            startTime, endTime, status, notes, createdAt, updatedAt,
        )
    }
}
