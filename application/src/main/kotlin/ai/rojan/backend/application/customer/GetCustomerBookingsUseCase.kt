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
import ai.rojan.backend.domain.customer.CustomerId
import ai.rojan.backend.domain.customer.CustomerRepository
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId

data class GetCustomerBookingsCommand(
    val customerId: CustomerId,
    val callerId: UserId,
    val pageRequest: PageRequest,
    val statusFilter: BookingStatus?,
    val sortDirection: SortDirection,
)

/**
 * The owner-facing "bookings for this specific customer" capability
 * flagged as missing in `ROJAN_Booking_CRM_Integration_Plan_v1.md` (blocker
 * #3) - resolved here via [BookingRepository.findBySalonCustomerId] against
 * the CRM [ai.rojan.backend.domain.customer.CustomerId] the booking is
 * anchored to ([ai.rojan.backend.domain.booking.Booking.salonCustomerId]),
 * scoped to this customer's own [ai.rojan.backend.domain.salon.SalonId] -
 * never the platform-wide [BookingRepository.findByCustomerId] that powers
 * the self-service `GET /bookings/mine`. A person's bookings at other salons
 * carry a different `salonCustomerId`, so they can never leak into this
 * salon's view (`ROJAN_Customer_Booking_History_Tenant_Isolation_Fix_Report_v1.md`).
 * BACKEND-CRM-READ-MIGRATION-001: no more `Customer.userId` dependency - a
 * customer with no bookings (walk-in or otherwise) returns an empty page
 * naturally.
 */
class GetCustomerBookingsUseCase(
    private val salonRepository: SalonRepository,
    private val customerRepository: CustomerRepository,
    private val bookingRepository: BookingRepository,
) {
    fun execute(command: GetCustomerBookingsCommand): PageResult<Booking> {
        val customer = customerRepository.findById(command.customerId)
            ?: throw CustomerNotFoundException(command.customerId.value.toString())
        val salon = salonRepository.findById(customer.salonId)
            ?: throw SalonNotFoundException(customer.salonId.value.toString())
        if (salon.ownerId != command.callerId) {
            throw CustomerAccessDeniedException(customer.id.value.toString())
        }

        return bookingRepository.findBySalonCustomerId(
            customer.id,
            customer.salonId,
            command.pageRequest,
            command.statusFilter,
            command.sortDirection,
        )
    }
}
