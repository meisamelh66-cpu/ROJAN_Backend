package ai.rojan.backend.application.customer

import ai.rojan.backend.domain.booking.Booking
import ai.rojan.backend.domain.booking.BookingRepository
import ai.rojan.backend.domain.booking.BookingStatus
import ai.rojan.backend.domain.common.CustomerAccessDeniedException
import ai.rojan.backend.domain.common.CustomerNotFoundException
import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.common.SortDirection
import ai.rojan.backend.domain.customer.CustomerActivityRepository
import ai.rojan.backend.domain.customer.CustomerId
import ai.rojan.backend.domain.customer.CustomerNoteRepository
import ai.rojan.backend.domain.customer.CustomerRepository
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId
import java.time.Instant

data class GetCustomerTimelineCommand(val customerId: CustomerId, val callerId: UserId, val pageRequest: PageRequest)

data class TimelineEntry(val type: String, val description: String, val occurredAt: Instant)

/**
 * Merges the timeline at read time - [ai.rojan.backend.domain.customer.CustomerActivityRepository]
 * (status changes, tag add/remove), [CustomerNoteRepository] (every note is
 * itself a timeline entry), and booking lifecycle events read directly from
 * [BookingRepository] via [BookingRepository.findBySalonCustomerId] (keyed
 * on the CRM [CustomerId] the booking is anchored to; salon-scoped, so a
 * person's booking events at other salons never appear in this salon's
 * timeline; empty for a customer with no bookings, not an error) - rather
 * than writing a
 * physical row from every booking-status-transition use case, which would
 * couple the Booking module to Customer and risk a silently missed write.
 * See `ROJAN_Customer_CRM_Architecture_Plan_v1.md` §1.4.
 *
 * Booking events are deliberately limited to "created" + (if not still
 * PENDING) one "current status" entry, not a synthetic full transition
 * history - [Booking] only stores one `updatedAt`, overwritten on every
 * transition, so fabricating separate "confirmed"/"completed" timestamps
 * from it would show identical, misleading times for a booking that passed
 * through both.
 *
 * Pagination is in-memory over the merged, sorted list - the three sources
 * are each already bounded (a customer's own activities/notes/bookings,
 * not a system-wide scan), so this trades a small amount of paginate-after-
 * fetch inefficiency for a much simpler merge than a UNION query across
 * three different persistence adapters would need.
 */
class GetCustomerTimelineUseCase(
    private val salonRepository: SalonRepository,
    private val customerRepository: CustomerRepository,
    private val customerActivityRepository: CustomerActivityRepository,
    private val customerNoteRepository: CustomerNoteRepository,
    private val bookingRepository: BookingRepository,
) {
    fun execute(command: GetCustomerTimelineCommand): PageResult<TimelineEntry> {
        val customer = customerRepository.findById(command.customerId)
            ?: throw CustomerNotFoundException(command.customerId.value.toString())
        val salon = salonRepository.findById(customer.salonId)
            ?: throw SalonNotFoundException(customer.salonId.value.toString())
        if (salon.ownerId != command.callerId) {
            throw CustomerAccessDeniedException(customer.id.value.toString())
        }

        val activityEntries = customerActivityRepository.findByCustomerId(customer.id)
            .map { TimelineEntry(it.type.name, it.description, it.occurredAt) }

        val noteEntries = customerNoteRepository.findByCustomerId(customer.id)
            .map { TimelineEntry("NOTE", it.text, it.createdAt) }

        val bookingEntries = bookingRepository
            .findBySalonCustomerId(customer.id, customer.salonId, PageRequest(0, PageRequest.MAX_SIZE), statusFilter = null, SortDirection.DESC)
            .content
            .flatMap { bookingTimelineEntriesFor(it) }

        val merged = (activityEntries + noteEntries + bookingEntries).sortedByDescending { it.occurredAt }
        return paginate(merged, command.pageRequest)
    }

    private fun bookingTimelineEntriesFor(booking: Booking): List<TimelineEntry> {
        val entries = mutableListOf(TimelineEntry("BOOKING_CREATED", "Booking created for ${booking.startTime}", booking.createdAt))
        if (booking.status != BookingStatus.PENDING) {
            entries += TimelineEntry("BOOKING_${booking.status}", "Booking ${booking.status.name.lowercase()}", booking.updatedAt)
        }
        return entries
    }

    private fun paginate(all: List<TimelineEntry>, pageRequest: PageRequest): PageResult<TimelineEntry> {
        val from = (pageRequest.page * pageRequest.size).coerceAtMost(all.size)
        val to = (from + pageRequest.size).coerceAtMost(all.size)
        return PageResult(content = all.subList(from, to), page = pageRequest.page, size = pageRequest.size, totalElements = all.size.toLong())
    }
}
