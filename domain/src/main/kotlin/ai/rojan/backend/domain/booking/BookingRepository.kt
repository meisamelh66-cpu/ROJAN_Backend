package ai.rojan.backend.domain.booking

import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.common.SortDirection
import ai.rojan.backend.domain.customer.CustomerId
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

    /**
     * A CRM customer's bookings, by the salon-scoped
     * [ai.rojan.backend.domain.customer.CustomerId] the booking is anchored
     * to ([Booking.salonCustomerId], BACKEND-CRM-CUSTOMER-IDENTITY-001).
     * Use this, never [findByCustomerId], for any salon-owner-facing view of
     * a specific customer (booking history, lifetime value, timeline): a
     * `CustomerId` belongs to exactly one salon, and a person's bookings at
     * other salons carry a different `salonCustomerId`, so they can never
     * leak into this salon's view. [salonId] is retained as a
     * defense-in-depth filter.
     */
    fun findBySalonCustomerId(
        salonCustomerId: CustomerId,
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
     * Production Hardening Phase 1: batched sibling of [findBySalonCustomerId],
     * completed bookings only, for every CRM [salonCustomerIds] on a paginated
     * customer-list page in one query - powers
     * [ai.rojan.backend.application.customer.CalculateCustomerLifetimeValueUseCase]'s
     * per-customer computation without querying per row. Same salon-scoping
     * requirement as [findBySalonCustomerId] - never platform-wide.
     */
    fun findCompletedBySalonIdAndSalonCustomerIdIn(salonId: SalonId, salonCustomerIds: Collection<CustomerId>): List<Booking>

    /**
     * Every distinct customer who has at least one booking with [salonId]
     * — the salon-scoped customer roster the legacy
     * `GET /api/v1/salons/{salonId}/customers` endpoint
     * ([ai.rojan.backend.api.salon.SalonCustomerController]) searches
     * against. Regardless of booking status. Restored by
     * POST-MERGE-API-COMPATIBILITY-FIX-001 (the CRM recovery dropped it
     * together with that controller; both are back, unchanged).
     */
    fun findDistinctCustomerIdsBySalonId(salonId: SalonId): List<UserId>
}
