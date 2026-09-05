package ai.rojan.backend.domain.booking

import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.common.SortDirection
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

    /** A salon's bookings, optionally filtered by status, sorted by start time. */
    fun findBySalonId(
        salonId: SalonId,
        pageRequest: PageRequest,
        statusFilter: BookingStatus?,
        sortDirection: SortDirection,
    ): PageResult<Booking>

    /** A customer's bookings, optionally filtered by status, sorted by start time. */
    fun findByCustomerId(
        customerId: UserId,
        pageRequest: PageRequest,
        statusFilter: BookingStatus?,
        sortDirection: SortDirection,
    ): PageResult<Booking>

    /** Active (pending/confirmed) bookings for [specialistId] overlapping [from]..[to], used by the slot engine. */
    fun findActiveBySpecialistIdAndDateRange(
        specialistId: SpecialistId,
        from: LocalDateTime,
        to: LocalDateTime,
    ): List<Booking>

    /**
     * Every distinct customer who has at least one booking with [salonId]
     * — the salon-scoped customer roster a receptionist/manager creating
     * a booking on someone's behalf searches against. Regardless of
     * booking status: a cancelled booking still means the person is a
     * real, known customer of this salon.
     */
    fun findDistinctCustomerIdsBySalonId(salonId: SalonId): List<UserId>
}
