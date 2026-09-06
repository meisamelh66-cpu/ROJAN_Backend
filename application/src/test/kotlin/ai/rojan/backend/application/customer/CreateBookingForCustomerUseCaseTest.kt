package ai.rojan.backend.application.customer

import ai.rojan.backend.application.booking.CreateBookingUseCase
import ai.rojan.backend.application.booking.InMemoryBookingRepository
import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.application.salon.InMemoryServiceRepository
import ai.rojan.backend.application.salon.InMemorySpecialistRepository
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.BookingConflictException
import ai.rojan.backend.domain.common.CustomerNotFoundException
import ai.rojan.backend.domain.common.CustomerNotLinkedToAccountException
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.customer.Customer
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.Service
import ai.rojan.backend.domain.salon.ServiceCategoryId
import ai.rojan.backend.domain.salon.Specialist
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * ROJAN Reception Booking Flow (Phase 0). Exercises
 * [CreateBookingForCustomerUseCase] in isolation - authorization, tenant
 * isolation (customer must belong to the salon in the command), the
 * unlinked-customer rejection, and that it genuinely delegates to (rather
 * than reimplements) [CreateBookingUseCase]'s own double-booking conflict
 * check.
 */
class CreateBookingForCustomerUseCaseTest {

    private val salonRepository = InMemorySalonRepository()
    private val customerRepository = InMemoryCustomerRepository()
    private val serviceRepository = InMemoryServiceRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val bookingRepository = InMemoryBookingRepository()
    private val createBookingUseCase = CreateBookingUseCase(salonRepository, serviceRepository, specialistRepository, bookingRepository)
    private val useCase = CreateBookingForCustomerUseCase(salonRepository, customerRepository, createBookingUseCase)

    private val ownerId = UserId.new()
    private val salon = Salon.create(ownerId, "Test Salon", null, "0912", null, "Address").also { salonRepository.save(it) }
    private val service = Service.create(salon.id, ServiceCategoryId.new(), "Haircut", null, 30, BigDecimal("25.00"))
        .also { serviceRepository.save(it) }
    private val specialist = Specialist.create(salon.id, null, "Kiana", null, null).also { specialistRepository.save(it) }

    @Test
    fun `creates a booking for a linked customer`() {
        val linkedUserId = UserId.new()
        val customer = Customer.create(salon.id, linkedUserId, "Jane Doe", PhoneNumber("+989123456789"), null, null)
            .also { customerRepository.save(it) }

        val booking = useCase.execute(
            CreateBookingForCustomerCommand(
                salon.id, ownerId, customer.id, service.id, specialist.id,
                LocalDateTime.of(2026, 9, 1, 10, 0), "Walk-in",
            ),
        )

        assertEquals(linkedUserId, booking.customerId)
        assertEquals(salon.id, booking.salonId)
        assertEquals(bookingRepository.findById(booking.id), booking)
    }

    @Test
    fun `rejects a customer with no linked account`() {
        val customer = Customer.create(salon.id, null, "Walk-in Jane", PhoneNumber("+989123456780"), null, null)
            .also { customerRepository.save(it) }

        assertThrows<CustomerNotLinkedToAccountException> {
            useCase.execute(
                CreateBookingForCustomerCommand(
                    salon.id, ownerId, customer.id, service.id, specialist.id,
                    LocalDateTime.of(2026, 9, 1, 10, 0), null,
                ),
            )
        }
    }

    @Test
    fun `rejects a caller who does not own the salon`() {
        val customer = Customer.create(salon.id, UserId.new(), "Jane Doe", PhoneNumber("+989123456789"), null, null)
            .also { customerRepository.save(it) }

        assertThrows<SalonAccessDeniedException> {
            useCase.execute(
                CreateBookingForCustomerCommand(
                    salon.id, UserId.new(), customer.id, service.id, specialist.id,
                    LocalDateTime.of(2026, 9, 1, 10, 0), null,
                ),
            )
        }
    }

    @Test
    fun `rejects a customer that belongs to a different salon - tenant isolation`() {
        val otherSalon = Salon.create(ownerId, "Other Salon", null, "0913", null, "Other Address").also { salonRepository.save(it) }
        val customerOfOtherSalon = Customer.create(otherSalon.id, UserId.new(), "Jane Doe", PhoneNumber("+989123456789"), null, null)
            .also { customerRepository.save(it) }

        assertThrows<CustomerNotFoundException> {
            useCase.execute(
                CreateBookingForCustomerCommand(
                    salon.id, ownerId, customerOfOtherSalon.id, service.id, specialist.id,
                    LocalDateTime.of(2026, 9, 1, 10, 0), null,
                ),
            )
        }
    }

    @Test
    fun `propagates the same double-booking conflict CreateBookingUseCase already enforces`() {
        val firstCustomer = Customer.create(salon.id, UserId.new(), "First", PhoneNumber("+989123456781"), null, null)
            .also { customerRepository.save(it) }
        val secondCustomer = Customer.create(salon.id, UserId.new(), "Second", PhoneNumber("+989123456782"), null, null)
            .also { customerRepository.save(it) }
        val startTime = LocalDateTime.of(2026, 9, 1, 10, 0)

        useCase.execute(CreateBookingForCustomerCommand(salon.id, ownerId, firstCustomer.id, service.id, specialist.id, startTime, null))

        assertThrows<BookingConflictException> {
            useCase.execute(CreateBookingForCustomerCommand(salon.id, ownerId, secondCustomer.id, service.id, specialist.id, startTime, null))
        }
    }
}
