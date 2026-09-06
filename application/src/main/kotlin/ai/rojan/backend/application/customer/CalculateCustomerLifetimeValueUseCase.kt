package ai.rojan.backend.application.customer

import ai.rojan.backend.domain.booking.BookingRepository
import ai.rojan.backend.domain.booking.BookingStatus
import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.SortDirection
import ai.rojan.backend.domain.customer.Customer
import ai.rojan.backend.domain.salon.ServiceRepository
import java.math.BigDecimal

/**
 * Computed on every read, never stored - see
 * `ROJAN_Customer_CRM_Architecture_Plan_v1.md` §1.5 for why: no cache-
 * invalidation risk, correct by construction, at the cost of re-summing on
 * every call (acceptable at Phase 1's expected data volumes; a cached,
 * recompute-on-completion column is a contained future optimization if it
 * ever isn't). Zero for a customer with no linked account - there is no
 * booking data to sum (see [Customer.userId]'s own doc comment). Scoped to
 * this customer's own [Customer.salonId] - a linked account's completed
 * bookings at other salons must not inflate the lifetime value this
 * salon's owner sees (`ROJAN_Customer_Booking_History_Tenant_Isolation_Fix_Report_v1.md`).
 *
 * Takes an already-resolved, already-authorized [Customer] directly rather
 * than a Command with raw ids - this is a pure computation reused by both
 * the list and detail mappings in `CustomerController`, which have already
 * done the salon-ownership check by the time they call this.
 */
class CalculateCustomerLifetimeValueUseCase(
    private val bookingRepository: BookingRepository,
    private val serviceRepository: ServiceRepository,
) {
    fun execute(customer: Customer): BigDecimal {
        val userId = customer.userId ?: return BigDecimal.ZERO

        val completedBookings = bookingRepository
            .findByCustomerIdAndSalonId(userId, customer.salonId, PageRequest(0, PageRequest.MAX_SIZE), BookingStatus.COMPLETED, SortDirection.DESC)
            .content

        return completedBookings.sumOf { booking -> serviceRepository.findById(booking.serviceId)?.price ?: BigDecimal.ZERO }
    }
}
