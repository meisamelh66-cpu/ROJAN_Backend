package ai.rojan.backend.application.customer

import ai.rojan.backend.application.booking.InMemoryBookingRepository
import ai.rojan.backend.application.salon.InMemoryServiceRepository
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.booking.Booking
import ai.rojan.backend.domain.customer.Customer
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.Service
import ai.rojan.backend.domain.salon.ServiceCategoryId
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class CalculateCustomerLifetimeValueUseCaseTest {

    private val bookingRepository = InMemoryBookingRepository()
    private val serviceRepository = InMemoryServiceRepository()
    private val useCase = CalculateCustomerLifetimeValueUseCase(bookingRepository, serviceRepository)

    private val salonId = SalonId.new()

    @Test
    fun `is zero for a customer with no linked account`() {
        val customer = Customer.create(salonId, null, "Jane Doe", PhoneNumber("+989123456789"), null, null)

        assertEquals(BigDecimal.ZERO, useCase.execute(customer))
    }

    @Test
    fun `sums the price of every completed booking's service`() {
        val userId = UserId.new()
        val customer = Customer.create(salonId, userId, "Jane Doe", PhoneNumber("+989123456789"), null, null)

        val haircut = Service.create(salonId, ServiceCategoryId.new(), "Haircut", null, 30, BigDecimal("650000")).also { serviceRepository.save(it) }
        val manicure = Service.create(salonId, ServiceCategoryId.new(), "Manicure", null, 45, BigDecimal("400000")).also { serviceRepository.save(it) }

        val completed1 = Booking.create(salonId, haircut.id, SpecialistId.new(), userId, LocalDateTime.of(2026, 1, 1, 9, 0), LocalDateTime.of(2026, 1, 1, 9, 30), null)
        completed1.confirm(); completed1.complete()
        bookingRepository.reserve(completed1)

        val completed2 = Booking.create(salonId, manicure.id, SpecialistId.new(), userId, LocalDateTime.of(2026, 2, 1, 9, 0), LocalDateTime.of(2026, 2, 1, 9, 45), null)
        completed2.confirm(); completed2.complete()
        bookingRepository.reserve(completed2)

        val stillPending = Booking.create(salonId, haircut.id, SpecialistId.new(), userId, LocalDateTime.of(2026, 3, 1, 9, 0), LocalDateTime.of(2026, 3, 1, 9, 30), null)
        bookingRepository.reserve(stillPending)

        val lifetimeValue = useCase.execute(customer)

        assertEquals(BigDecimal("1050000"), lifetimeValue) // 650000 + 400000, pending booking excluded
    }
}
