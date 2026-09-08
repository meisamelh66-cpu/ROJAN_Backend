package ai.rojan.backend.application.booking

import ai.rojan.backend.domain.booking.Booking
import ai.rojan.backend.domain.booking.BookingId
import ai.rojan.backend.domain.booking.BookingRepository
import ai.rojan.backend.domain.common.BookingAccessDeniedException
import ai.rojan.backend.domain.common.BookingNotFoundException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.common.ServiceNotFoundException
import ai.rojan.backend.domain.common.SpecialistNotFoundException
import ai.rojan.backend.domain.customer.CustomerId
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.ServiceId
import ai.rojan.backend.domain.salon.ServiceRepository
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.salon.SpecialistRepository
import ai.rojan.backend.domain.user.UserId
import java.time.LocalDateTime

data class CreateBookingCommand(
    val salonId: SalonId,
    val serviceId: ServiceId,
    val specialistId: SpecialistId,
    val customerId: UserId,
    val startTime: LocalDateTime,
    val notes: String?,
    /**
     * BACKEND-CRM-CUSTOMER-IDENTITY-001: the salon's CRM record this booking
     * belongs to. Resolved by the orchestration layer before the command is
     * built - `BookingController` (self-service, via
     * `ResolveOrCreateSalonCustomerUseCase`) and `CreateBookingForCustomerUseCase`
     * (owner-on-behalf, from the already-loaded customer). `null` only for
     * internal/legacy call paths that predate the CRM anchor; such bookings
     * simply carry no `salonCustomerId` until the backfill runs.
     */
    val salonCustomerId: CustomerId? = null,
)

class CreateBookingUseCase(
    private val salonRepository: SalonRepository,
    private val serviceRepository: ServiceRepository,
    private val specialistRepository: SpecialistRepository,
    private val bookingRepository: BookingRepository,
) {
    fun execute(command: CreateBookingCommand): Booking {
        val salon = salonRepository.findById(command.salonId)?.takeIf { it.active }
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        val service = serviceRepository.findById(command.serviceId)
            ?.takeIf { it.salonId == salon.id && it.active }
            ?: throw ServiceNotFoundException(command.serviceId.value.toString())
        val specialist = specialistRepository.findById(command.specialistId)
            ?.takeIf { it.salonId == salon.id && it.active }
            ?: throw SpecialistNotFoundException(command.specialistId.value.toString())

        val endTime = command.startTime.plusMinutes(service.durationMinutes.toLong())
        val booking = Booking.create(
            salonId = salon.id,
            serviceId = service.id,
            specialistId = specialist.id,
            customerId = command.customerId,
            startTime = command.startTime,
            endTime = endTime,
            notes = command.notes,
            salonCustomerId = command.salonCustomerId,
        )
        return bookingRepository.reserve(booking)
    }
}

data class ConfirmBookingCommand(val bookingId: BookingId, val callerId: UserId)

class ConfirmBookingUseCase(
    private val bookingRepository: BookingRepository,
    private val salonRepository: SalonRepository,
) {
    fun execute(command: ConfirmBookingCommand): Booking {
        val booking = findBookingOrThrow(bookingRepository, command.bookingId)
        requireOwner(salonRepository, booking, command.callerId)
        booking.confirm()
        return bookingRepository.save(booking)
    }
}

data class CancelBookingCommand(val bookingId: BookingId, val callerId: UserId)

class CancelBookingUseCase(
    private val bookingRepository: BookingRepository,
    private val salonRepository: SalonRepository,
) {
    fun execute(command: CancelBookingCommand): Booking {
        val booking = findBookingOrThrow(bookingRepository, command.bookingId)
        requireCustomerOrOwner(salonRepository, booking, command.callerId)
        booking.cancel()
        return bookingRepository.save(booking)
    }
}

data class CompleteBookingCommand(val bookingId: BookingId, val callerId: UserId)

class CompleteBookingUseCase(
    private val bookingRepository: BookingRepository,
    private val salonRepository: SalonRepository,
) {
    fun execute(command: CompleteBookingCommand): Booking {
        val booking = findBookingOrThrow(bookingRepository, command.bookingId)
        requireOwner(salonRepository, booking, command.callerId)
        booking.complete()
        return bookingRepository.save(booking)
    }
}

data class RescheduleBookingCommand(
    val bookingId: BookingId,
    val callerId: UserId,
    val newStartTime: LocalDateTime,
)

class RescheduleBookingUseCase(
    private val bookingRepository: BookingRepository,
    private val salonRepository: SalonRepository,
    private val serviceRepository: ServiceRepository,
) {
    fun execute(command: RescheduleBookingCommand): Booking {
        val booking = findBookingOrThrow(bookingRepository, command.bookingId)
        requireCustomerOrOwner(salonRepository, booking, command.callerId)
        val service = serviceRepository.findById(booking.serviceId)
            ?: throw ServiceNotFoundException(booking.serviceId.value.toString())

        val newEndTime = command.newStartTime.plusMinutes(service.durationMinutes.toLong())
        booking.reschedule(command.newStartTime, newEndTime)
        return bookingRepository.reserve(booking, excludeBookingId = booking.id)
    }
}

private fun findBookingOrThrow(bookingRepository: BookingRepository, bookingId: BookingId): Booking =
    bookingRepository.findById(bookingId) ?: throw BookingNotFoundException(bookingId.value.toString())

private fun requireOwner(salonRepository: SalonRepository, booking: Booking, callerId: UserId) {
    val salon = salonRepository.findById(booking.salonId)
        ?: throw SalonNotFoundException(booking.salonId.value.toString())
    if (salon.ownerId != callerId) throw BookingAccessDeniedException(booking.id.value.toString())
}

private fun requireCustomerOrOwner(salonRepository: SalonRepository, booking: Booking, callerId: UserId) {
    if (booking.customerId == callerId) return
    requireOwner(salonRepository, booking, callerId)
}
