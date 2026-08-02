package ai.rojan.backend.domain.booking

import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.user.UserId
import java.time.LocalDateTime

interface BookingRepository {
    /**
     * Atomically checks for overlapping active (pending/confirmed) bookings
     * for [booking]'s specialist and persists it only if none are found,
     * throwing [ai.rojan.backend.domain.common.BookingConflictException]
     * otherwise. [excludeBookingId] lets a reschedule ignore the booking's
     * own prior slot. Implementations must serialize concurrent reservations
     * for the same specialist so this is safe under concurrent access.
     */
    fun reserve(booking: Booking, excludeBookingId: BookingId? = null): Booking

    /** Persists a status-only change (no time change, so no conflict re-check is needed). */
    fun save(booking: Booking): Booking

    fun findById(id: BookingId): Booking?
    fun findBySalonId(salonId: SalonId): List<Booking>
    fun findByCustomerId(customerId: UserId): List<Booking>

    /** Active (pending/confirmed) bookings for [specialistId] overlapping [from]..[to], used by the slot engine. */
    fun findActiveBySpecialistIdAndDateRange(
        specialistId: SpecialistId,
        from: LocalDateTime,
        to: LocalDateTime,
    ): List<Booking>
}
