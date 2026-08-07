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

    /**
     * A customer's bookings across every salon, optionally filtered by
     * status, sorted by start time - platform-wide by design, for the
     * customer's own self-service "my bookings" view
     * ([ai.rojan.backend.api.booking.BookingController.mine]), where seeing
     * every salon they've booked at is exactly correct.
     */
    fun findByCustomerId(
        customerId: UserId,
        pageRequest: PageRequest,
        statusFilter: BookingStatus?,
        sortDirection: SortDirection,
    ): PageResult<Booking>

    /**
     * A customer's bookings at **one specific salon only**, optionally
     * filtered by status, sorted by start time. Use this, never
     * [findByCustomerId], for any salon-owner-facing view of a specific
     * customer (booking history, lifetime value, timeline) - a linked
     * customer may have bookings at other salons too, and those must never
     * be visible to a salon that isn't theirs.
     */
    fun findByCustomerIdAndSalonId(
        customerId: UserId,
        salonId: SalonId,
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

    /** A salon's bookings (any status) starting in [from] (inclusive)..[to] (exclusive), used for dashboard analytics. */
    fun findBySalonIdAndStartTimeRange(salonId: SalonId, from: LocalDateTime, to: LocalDateTime): List<Booking>

    /** Which of [customerIds] have a booking with [salonId] starting before [before] — used to classify new vs. returning customers. */
    fun findCustomerIdsWithBookingBefore(salonId: SalonId, customerIds: Set<UserId>, before: LocalDateTime): Set<UserId>

    /**
     * Production Hardening Phase 1: batched sibling of [findByCustomerIdAndSalonId],
     * completed bookings only, for every [customerIds] on a paginated customer-list
     * page in one query - powers [ai.rojan.backend.application.customer.CalculateCustomerLifetimeValueUseCase]'s
     * per-customer computation without querying per row. Same salon-scoping
     * requirement as [findByCustomerIdAndSalonId] - never platform-wide.
     */
    fun findCompletedBySalonIdAndCustomerIdIn(salonId: SalonId, customerIds: Collection<UserId>): List<Booking>
}
