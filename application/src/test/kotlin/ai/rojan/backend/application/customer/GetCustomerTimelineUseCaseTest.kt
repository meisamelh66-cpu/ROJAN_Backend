package ai.rojan.backend.application.customer

import ai.rojan.backend.application.booking.InMemoryBookingRepository
import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.booking.Booking
import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.customer.Customer
import ai.rojan.backend.domain.customer.CustomerActivity
import ai.rojan.backend.domain.customer.CustomerActivityType
import ai.rojan.backend.domain.customer.CustomerNote
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.ServiceId
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

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

    @Test
    fun `merges activities, notes, and booking events into one sorted feed`() {
        val linkedUserId = UserId.new()
        val customer = Customer.create(salon.id, linkedUserId, "Jane Doe", PhoneNumber("+989123456789"), null, null)
            .also { customerRepository.save(it) }

        customerActivityRepository.save(CustomerActivity.create(customer.id, CustomerActivityType.TAG_ADDED, "Tag added: VIP"))
        customerNoteRepository.save(CustomerNote.create(customer.id, ownerId, "Prefers morning appointments"))
        val booking = Booking.create(
            salon.id, ServiceId.new(), SpecialistId.new(), linkedUserId,
            LocalDateTime.of(2026, 8, 10, 9, 0), LocalDateTime.of(2026, 8, 10, 9, 30), null,
        )
        bookingRepository.reserve(booking)

        val result = useCase.execute(GetCustomerTimelineCommand(customer.id, ownerId, PageRequest(0, 20)))

        // 1 activity + 1 note + 1 "booking created" event (booking is still PENDING, so no second status entry)
        assertEquals(3, result.totalElements)
        assertTrue(result.content.any { it.type == "TAG_ADDED" })
        assertTrue(result.content.any { it.type == "NOTE" })
        assertTrue(result.content.any { it.type == "BOOKING_CREATED" })
    }

    @Test
    fun `a confirmed booking contributes both a created and a confirmed entry`() {
        val linkedUserId = UserId.new()
        val customer = Customer.create(salon.id, linkedUserId, "Jane Doe", PhoneNumber("+989123456789"), null, null)
            .also { customerRepository.save(it) }
        val booking = Booking.create(
            salon.id, ServiceId.new(), SpecialistId.new(), linkedUserId,
            LocalDateTime.of(2026, 8, 10, 9, 0), LocalDateTime.of(2026, 8, 10, 9, 30), null,
        )
        booking.confirm()
        bookingRepository.reserve(booking)

        val result = useCase.execute(GetCustomerTimelineCommand(customer.id, ownerId, PageRequest(0, 20)))

        assertEquals(2, result.totalElements)
        assertTrue(result.content.any { it.type == "BOOKING_CREATED" })
        assertTrue(result.content.any { it.type == "BOOKING_CONFIRMED" })
    }

    @Test
    fun `does not include booking events from a different salon`() {
        val linkedUserId = UserId.new()
        val customer = Customer.create(salon.id, linkedUserId, "Jane Doe", PhoneNumber("+989123456789"), null, null)
            .also { customerRepository.save(it) }
        val ownBooking = Booking.create(
            salon.id, ServiceId.new(), SpecialistId.new(), linkedUserId,
            LocalDateTime.of(2026, 8, 10, 9, 0), LocalDateTime.of(2026, 8, 10, 9, 30), null,
        )
        bookingRepository.reserve(ownBooking)

        val otherSalon = Salon.create(UserId.new(), "Other Salon", null, "0913", null, "Other Address")
            .also { salonRepository.save(it) }
        val bookingAtOtherSalon = Booking.create(
            otherSalon.id, ServiceId.new(), SpecialistId.new(), linkedUserId,
            LocalDateTime.of(2026, 8, 11, 9, 0), LocalDateTime.of(2026, 8, 11, 9, 30), null,
        )
        bookingRepository.reserve(bookingAtOtherSalon)

        val result = useCase.execute(GetCustomerTimelineCommand(customer.id, ownerId, PageRequest(0, 20)))

        // only the own-salon booking's "created" event - the other salon's booking must not appear
        assertEquals(1, result.totalElements)
        assertEquals("BOOKING_CREATED", result.content[0].type)
    }

    @Test
    fun `an unlinked customer's timeline has no booking events, only CRM ones`() {
        val customer = Customer.create(salon.id, null, "Jane Doe", PhoneNumber("+989123456789"), null, null)
            .also { customerRepository.save(it) }
        customerNoteRepository.save(CustomerNote.create(customer.id, ownerId, "A note"))

        val result = useCase.execute(GetCustomerTimelineCommand(customer.id, ownerId, PageRequest(0, 20)))

        assertEquals(1, result.totalElements)
        assertEquals("NOTE", result.content[0].type)
    }
}
