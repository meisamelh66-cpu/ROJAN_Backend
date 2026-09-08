package ai.rojan.backend.application.customer

import ai.rojan.backend.application.booking.InMemoryBookingRepository
import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.booking.Booking
import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.customer.Customer
import ai.rojan.backend.domain.customer.CustomerActivity
import ai.rojan.backend.domain.customer.CustomerActivityType
import ai.rojan.backend.domain.customer.CustomerId
import ai.rojan.backend.domain.customer.CustomerNote
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.ServiceId
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/** BACKEND-CRM-READ-MIGRATION-001: booking timeline events are keyed on Booking.salonCustomerId, not Customer.userId. */
class GetCustomerTimelineUseCaseTest {

    private val salonRepository = InMemorySalonRepository()
    private val customerRepository = InMemoryCustomerRepository()
    private val customerActivityRepository = InMemoryCustomerActivityRepository()
    private val customerNoteRepository = InMemoryCustomerNoteRepository()
    private val bookingRepository = InMemoryBookingRepository()
    private val useCase = GetCustomerTimelineUseCase(
        salonRepository, customerRepository, customerActivityRepository, customerNoteRepository, bookingRepository,
    )

    private val ownerId = UserId.new()
    private val salon = Salon.create(ownerId, "Test Salon", null, "0912", null, "Address").also { salonRepository.save(it) }

    private fun seedBooking(salonId: SalonId, salonCustomerId: CustomerId, day: Int = 10, confirm: Boolean = false): Booking =
        Booking.create(
            salonId, ServiceId.new(), SpecialistId.new(), UserId.new(),
            LocalDateTime.of(2026, 8, day, 9, 0), LocalDateTime.of(2026, 8, day, 9, 30), null,
            salonCustomerId = salonCustomerId,
        ).also { if (confirm) it.confirm(); bookingRepository.reserve(it) }

    @Test
    fun `merges activities, notes, and booking events into one sorted feed`() {
        val customer = Customer.create(salon.id, UserId.new(), "Jane Doe", PhoneNumber("+989123456789"), null, null)
            .also { customerRepository.save(it) }

        customerActivityRepository.save(CustomerActivity.create(customer.id, CustomerActivityType.TAG_ADDED, "Tag added: VIP"))
        customerNoteRepository.save(CustomerNote.create(customer.id, ownerId, "Prefers morning appointments"))
        seedBooking(salon.id, customer.id)

        val result = useCase.execute(GetCustomerTimelineCommand(customer.id, ownerId, PageRequest(0, 20)))

        assertEquals(3, result.totalElements) // 1 activity + 1 note + 1 "booking created" (still PENDING)
        assertTrue(result.content.any { it.type == "TAG_ADDED" })
        assertTrue(result.content.any { it.type == "NOTE" })
        assertTrue(result.content.any { it.type == "BOOKING_CREATED" })
    }

    @Test
    fun `a confirmed booking contributes both a created and a confirmed entry`() {
        val customer = Customer.create(salon.id, UserId.new(), "Jane Doe", PhoneNumber("+989123456789"), null, null)
            .also { customerRepository.save(it) }
        seedBooking(salon.id, customer.id, confirm = true)

        val result = useCase.execute(GetCustomerTimelineCommand(customer.id, ownerId, PageRequest(0, 20)))

        assertEquals(2, result.totalElements)
        assertTrue(result.content.any { it.type == "BOOKING_CREATED" })
        assertTrue(result.content.any { it.type == "BOOKING_CONFIRMED" })
    }

    @Test
    fun `does not include booking events from the same person's record at another salon`() {
        val customer = Customer.create(salon.id, UserId.new(), "Jane Doe", PhoneNumber("+989123456789"), null, null)
            .also { customerRepository.save(it) }
        seedBooking(salon.id, customer.id, day = 10)

        val otherSalon = Salon.create(UserId.new(), "Other Salon", null, "0913", null, "Other Address")
            .also { salonRepository.save(it) }
        seedBooking(otherSalon.id, CustomerId.new(), day = 11)

        val result = useCase.execute(GetCustomerTimelineCommand(customer.id, ownerId, PageRequest(0, 20)))

        assertEquals(1, result.totalElements)
        assertEquals("BOOKING_CREATED", result.content[0].type)
    }

    @Test
    fun `a customer with no bookings has only CRM timeline entries`() {
        val customer = Customer.create(salon.id, null, "Jane Doe", PhoneNumber("+989123456789"), null, null)
            .also { customerRepository.save(it) }
        customerNoteRepository.save(CustomerNote.create(customer.id, ownerId, "A note"))

        val result = useCase.execute(GetCustomerTimelineCommand(customer.id, ownerId, PageRequest(0, 20)))

        assertEquals(1, result.totalElements)
        assertEquals("NOTE", result.content[0].type)
    }
}
