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
 * ever isn't). Zero for a customer with no completed bookings. Scoped to
 * this customer's own [Customer.salonId] via the CRM
 * [ai.rojan.backend.domain.customer.CustomerId] the booking is anchored to
 * (BACKEND-CRM-READ-MIGRATION-001) - a person's completed bookings at other
 * salons carry a different `salonCustomerId` and never inflate the lifetime
 * value this salon's owner sees (`ROJAN_Customer_Booking_History_Tenant_Isolation_Fix_Report_v1.md`).
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
        val completedBookings = bookingRepository
            .findBySalonCustomerId(customer.id, customer.salonId, PageRequest(0, PageRequest.MAX_SIZE), BookingStatus.COMPLETED, SortDirection.DESC)
            .content
        if (completedBookings.isEmpty()) return BigDecimal.ZERO

        // BACKEND-CRM-READ-MIGRATION-001: one services-for-the-salon query + local lookup,
        // never a per-booking serviceRepository.findById (the list path already does this).
        val priceByServiceId = serviceRepository.findBySalonId(customer.salonId).associate { it.id to it.price }
        return completedBookings.sumOf { priceByServiceId[it.serviceId] ?: BigDecimal.ZERO }
    }
}
