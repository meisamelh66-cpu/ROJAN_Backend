package ai.rojan.backend.application.customer

import ai.rojan.backend.application.booking.InMemoryBookingRepository
import ai.rojan.backend.application.salon.InMemoryServiceRepository
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.booking.Booking
import ai.rojan.backend.domain.customer.Customer
import ai.rojan.backend.domain.customer.CustomerId
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.Service
import ai.rojan.backend.domain.salon.ServiceCategoryId
import ai.rojan.backend.domain.salon.ServiceId
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

/** BACKEND-CRM-READ-MIGRATION-001: LTV sums completed bookings keyed on Booking.salonCustomerId; service prices are batch-loaded, not per-booking. */
class CalculateCustomerLifetimeValueUseCaseTest {

    private val bookingRepository = InMemoryBookingRepository()
    private val serviceRepository = InMemoryServiceRepository()
    private val useCase = CalculateCustomerLifetimeValueUseCase(bookingRepository, serviceRepository)

    private val salonId = SalonId.new()

    private fun completedBooking(salonId: SalonId, serviceId: ServiceId, salonCustomerId: CustomerId, day: Int): Booking =
        Booking.create(
            salonId, serviceId, SpecialistId.new(), UserId.new(),
            LocalDateTime.of(2026, day, 1, 9, 0), LocalDateTime.of(2026, day, 1, 9, 30), null,
            salonCustomerId = salonCustomerId,
        ).also { it.confirm(); it.complete(); bookingRepository.reserve(it) }

    @Test
    fun `is zero for a customer with no completed bookings`() {
        val customer = Customer.create(salonId, null, "Jane Doe", PhoneNumber("+989123456789"), null, null)

        assertEquals(BigDecimal.ZERO, useCase.execute(customer))
    }

    @Test
    fun `sums the price of every completed booking's service`() {
        val customer = Customer.create(salonId, UserId.new(), "Jane Doe", PhoneNumber("+989123456789"), null, null)

        val haircut = Service.create(salonId, ServiceCategoryId.new(), "Haircut", null, 30, BigDecimal("650000")).also { serviceRepository.save(it) }
        val manicure = Service.create(salonId, ServiceCategoryId.new(), "Manicure", null, 45, BigDecimal("400000")).also { serviceRepository.save(it) }

        completedBooking(salonId, haircut.id, customer.id, day = 1)
        completedBooking(salonId, manicure.id, customer.id, day = 2)

        val stillPending = Booking.create(
            salonId, haircut.id, SpecialistId.new(), UserId.new(),
            LocalDateTime.of(2026, 3, 1, 9, 0), LocalDateTime.of(2026, 3, 1, 9, 30), null,
            salonCustomerId = customer.id,
        )
        bookingRepository.reserve(stillPending)

        assertEquals(BigDecimal("1050000"), useCase.execute(customer)) // 650000 + 400000, pending excluded
    }

    @Test
    fun `excludes completed bookings anchored to the same person's record at another salon`() {
        val customer = Customer.create(salonId, UserId.new(), "Jane Doe", PhoneNumber("+989123456789"), null, null)

        val haircut = Service.create(salonId, ServiceCategoryId.new(), "Haircut", null, 30, BigDecimal("650000")).also { serviceRepository.save(it) }
        completedBooking(salonId, haircut.id, customer.id, day = 1)

        val otherSalonId = SalonId.new()
        val otherSalonService = Service.create(otherSalonId, ServiceCategoryId.new(), "Massage", null, 60, BigDecimal("2000000")).also { serviceRepository.save(it) }
        completedBooking(otherSalonId, otherSalonService.id, CustomerId.new(), day = 2)

        assertEquals(BigDecimal("650000"), useCase.execute(customer)) // the other salon's 2000000 must not be included
    }
}
