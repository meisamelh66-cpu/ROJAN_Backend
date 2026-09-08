package ai.rojan.backend.application.customer

import ai.rojan.backend.application.booking.InMemoryBookingRepository
import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.booking.Booking
import ai.rojan.backend.domain.common.CustomerAccessDeniedException
import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.SortDirection
import ai.rojan.backend.domain.customer.Customer
import ai.rojan.backend.domain.customer.CustomerId
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.ServiceId
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime

/**
 * BACKEND-CRM-READ-MIGRATION-001: booking history is keyed on
 * [Booking.salonCustomerId] (the CRM [CustomerId]), never [Customer.userId].
 */
class GetCustomerBookingsUseCaseTest {

    private val salonRepository = InMemorySalonRepository()
    private val customerRepository = InMemoryCustomerRepository()
    private val bookingRepository = InMemoryBookingRepository()
    private val useCase = GetCustomerBookingsUseCase(salonRepository, customerRepository, bookingRepository)

    private val ownerId = UserId.new()
    private val salon = Salon.create(ownerId, "Test Salon", null, "0912", null, "Address").also { salonRepository.save(it) }

    private fun seedBooking(salonId: SalonId, salonCustomerId: CustomerId, day: Int = 10): Booking = Booking.create(
        salonId, ServiceId.new(), SpecialistId.new(), UserId.new(),
        LocalDateTime.of(2026, 8, day, 9, 0), LocalDateTime.of(2026, 8, day, 9, 30), null,
        salonCustomerId = salonCustomerId,
    ).also { bookingRepository.reserve(it) }

    @Test
    fun `returns an empty page for a customer with no bookings, not an error`() {
        val customer = Customer.create(salon.id, null, "Jane Doe", PhoneNumber("+989123456789"), null, null)
            .also { customerRepository.save(it) }

        val result = useCase.execute(
            GetCustomerBookingsCommand(customer.id, ownerId, PageRequest(0, 20), null, SortDirection.DESC),
        )

        assertTrue(result.content.isEmpty())
        assertEquals(0, result.totalElements)
    }

    @Test
    fun `returns the customer's bookings, keyed on the CRM customer id`() {
        val customer = Customer.create(salon.id, UserId.new(), "Jane Doe", PhoneNumber("+989123456789"), null, null)
            .also { customerRepository.save(it) }
        val booking = seedBooking(salon.id, customer.id)

        val result = useCase.execute(
            GetCustomerBookingsCommand(customer.id, ownerId, PageRequest(0, 20), null, SortDirection.DESC),
        )

        assertEquals(1, result.content.size)
        assertEquals(booking.id, result.content[0].id)
    }

    @Test
    fun `does not leak the same person's bookings from a different salon`() {
        val customer = Customer.create(salon.id, UserId.new(), "Jane Doe", PhoneNumber("+989123456789"), null, null)
            .also { customerRepository.save(it) }
        val ownBooking = seedBooking(salon.id, customer.id, day = 10)

        // the same person's record at another salon has a different CustomerId
        val otherSalon = Salon.create(UserId.new(), "Other Salon", null, "0913", null, "Other Address")
            .also { salonRepository.save(it) }
        val bookingAtOtherSalon = seedBooking(otherSalon.id, CustomerId.new(), day = 11)

        val result = useCase.execute(
            GetCustomerBookingsCommand(customer.id, ownerId, PageRequest(0, 20), null, SortDirection.DESC),
        )

        assertEquals(1, result.content.size)
        assertEquals(ownBooking.id, result.content[0].id)
        assertTrue(result.content.none { it.id == bookingAtOtherSalon.id })
    }

    @Test
    fun `rejects a caller who does not own the salon`() {
        val customer = Customer.create(salon.id, null, "Jane Doe", PhoneNumber("+989123456789"), null, null)
            .also { customerRepository.save(it) }

        assertThrows<CustomerAccessDeniedException> {
            useCase.execute(GetCustomerBookingsCommand(customer.id, UserId.new(), PageRequest(0, 20), null, SortDirection.DESC))
        }
    }
}
