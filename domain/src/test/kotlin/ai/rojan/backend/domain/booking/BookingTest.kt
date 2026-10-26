package ai.rojan.backend.domain.booking

import ai.rojan.backend.domain.common.InvalidBookingStateException
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.ServiceId
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

private fun newBooking(): Booking = Booking.create(
    salonId = SalonId.new(),
    serviceId = ServiceId.new(),
    specialistId = SpecialistId.new(),
    customerId = UserId.new(),
    startTime = LocalDateTime.of(2026, 8, 10, 9, 0),
    endTime = LocalDateTime.of(2026, 8, 10, 9, 30),
    notes = null,
)

class BookingTest {

    @Test
    fun `is created as pending`() {
        val booking = newBooking()
        assertEquals(BookingStatus.PENDING, booking.status)
        assertEquals(true, booking.isActive)
    }

    @Test
    fun `rejects a start time that is not before end`() {
        // A single captured instant reused for both arguments, rather than two separate
        // LocalDateTime.now() calls - those are only "equal" on a clock coarse enough that
        // back-to-back calls coincide, which doesn't hold on every platform/JVM (this was
        // flaky: reliably passed on a coarser local clock, reliably failed on Linux CI's
        // finer-grained one, since two separate now() calls there are always strictly
        // increasing - i.e. a *valid* start-before-end, which must NOT throw).
        val instant = LocalDateTime.now()
        assertThrows(IllegalArgumentException::class.java) {
            Booking.create(SalonId.new(), ServiceId.new(), SpecialistId.new(), UserId.new(), instant, instant, null)
        }
    }

    @Test
    fun `confirm moves a pending booking to confirmed`() {
        val booking = newBooking()
        booking.confirm()
        assertEquals(BookingStatus.CONFIRMED, booking.status)
    }

    @Test
    fun `confirm rejects a booking that is not pending`() {
        val booking = newBooking()
        booking.confirm()
        assertThrows(InvalidBookingStateException::class.java) { booking.confirm() }
    }

    @Test
    fun `cancel is allowed from pending or confirmed`() {
        val pending = newBooking()
        pending.cancel()
        assertEquals(BookingStatus.CANCELLED, pending.status)

        val confirmed = newBooking()
        confirmed.confirm()
        confirmed.cancel()
        assertEquals(BookingStatus.CANCELLED, confirmed.status)
    }

    @Test
    fun `cancel rejects an already-cancelled booking`() {
        val booking = newBooking()
        booking.cancel()
        assertThrows(InvalidBookingStateException::class.java) { booking.cancel() }
    }

    @Test
    fun `complete requires the booking to be confirmed first`() {
        val booking = newBooking()
        assertThrows(InvalidBookingStateException::class.java) { booking.complete() }

        booking.confirm()
        booking.complete()
        assertEquals(BookingStatus.COMPLETED, booking.status)
        assertEquals(false, booking.isActive)
    }

    @Test
    fun `reschedule updates the time window while pending or confirmed`() {
        val booking = newBooking()
        val newStart = LocalDateTime.of(2026, 8, 11, 14, 0)
        val newEnd = LocalDateTime.of(2026, 8, 11, 14, 30)
        booking.reschedule(newStart, newEnd)
        assertEquals(newStart, booking.startTime)
        assertEquals(newEnd, booking.endTime)
    }

    @Test
    fun `reschedule rejects a cancelled booking`() {
        val booking = newBooking()
        booking.cancel()
        assertThrows(InvalidBookingStateException::class.java) {
            booking.reschedule(LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(1).plusMinutes(30))
        }
    }

    @Test
    fun `reschedule rejects a start time that is not before end`() {
        val booking = newBooking()
        val same = LocalDateTime.of(2026, 8, 11, 14, 0)
        assertThrows(IllegalArgumentException::class.java) { booking.reschedule(same, same) }
    }
}
