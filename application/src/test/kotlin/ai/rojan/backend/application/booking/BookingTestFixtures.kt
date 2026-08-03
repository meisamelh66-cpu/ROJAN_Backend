package ai.rojan.backend.application.booking

import ai.rojan.backend.domain.booking.Booking
import ai.rojan.backend.domain.booking.BookingId
import ai.rojan.backend.domain.booking.BookingRepository
import ai.rojan.backend.domain.booking.BookingStatus
import ai.rojan.backend.domain.common.BookingConflictException
import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.common.SortDirection
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.user.UserId
import java.time.LocalDateTime

/** Synchronous in-memory fake. True concurrency safety is exercised separately against a real database. */
internal class InMemoryBookingRepository : BookingRepository {
    private val store = mutableMapOf<BookingId, Booking>()

    override fun reserve(booking: Booking, excludeBookingId: BookingId?): Booking {
        val overlapping = store.values.any {
            it.specialistId == booking.specialistId &&
                it.isActive &&
                it.id != excludeBookingId &&
                it.startTime < booking.endTime && booking.startTime < it.endTime
        }
        if (overlapping) {
            throw BookingConflictException(booking.specialistId.value.toString(), booking.startTime.toString(), booking.endTime.toString())
        }
        store[booking.id] = booking
        return booking
    }

    override fun save(booking: Booking): Booking = booking.also { store[it.id] = it }

    override fun findById(id: BookingId): Booking? = store[id]

    override fun findBySalonId(
        salonId: SalonId,
        pageRequest: PageRequest,
        statusFilter: BookingStatus?,
        sortDirection: SortDirection,
    ): PageResult<Booking> = paginate(store.values.filter { it.salonId == salonId }, pageRequest, statusFilter, sortDirection)

    override fun findByCustomerId(
        customerId: UserId,
        pageRequest: PageRequest,
        statusFilter: BookingStatus?,
        sortDirection: SortDirection,
    ): PageResult<Booking> = paginate(store.values.filter { it.customerId == customerId }, pageRequest, statusFilter, sortDirection)

    private fun paginate(
        bookings: Collection<Booking>,
        pageRequest: PageRequest,
        statusFilter: BookingStatus?,
        sortDirection: SortDirection,
    ): PageResult<Booking> {
        val filtered = bookings
            .filter { statusFilter == null || it.status == statusFilter }
            .sortedBy { it.startTime }
            .let { if (sortDirection == SortDirection.DESC) it.reversed() else it }
        val fromIndex = (pageRequest.page * pageRequest.size).coerceAtMost(filtered.size)
        val toIndex = (fromIndex + pageRequest.size).coerceAtMost(filtered.size)
        return PageResult(
            content = filtered.subList(fromIndex, toIndex),
            page = pageRequest.page,
            size = pageRequest.size,
            totalElements = filtered.size.toLong(),
        )
    }

    override fun findActiveBySpecialistIdAndDateRange(specialistId: SpecialistId, from: LocalDateTime, to: LocalDateTime): List<Booking> =
        store.values.filter {
            it.specialistId == specialistId && it.isActive && it.startTime < to && it.endTime > from
        }
}
