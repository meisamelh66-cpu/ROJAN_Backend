package ai.rojan.backend.application.booking

import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.booking.Booking
import ai.rojan.backend.domain.booking.BookingId
import ai.rojan.backend.domain.booking.BookingRepository
import ai.rojan.backend.domain.common.BookingNotFoundException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.common.ServiceNotFoundException
import ai.rojan.backend.domain.common.SpecialistNotEligibleForServiceException
import ai.rojan.backend.domain.common.SpecialistNotFoundException
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.ServiceId
import ai.rojan.backend.domain.salon.ServiceRepository
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.salon.SpecialistRepository
import ai.rojan.backend.domain.salon.SpecialistServiceRepository
import ai.rojan.backend.domain.salon.isSpecialistEligibleForService
import ai.rojan.backend.domain.user.UserId
import java.time.LocalDateTime

data class CreateBookingCommand(
    val salonId: SalonId,
    val serviceId: ServiceId,
    val specialistId: SpecialistId,
    val customerId: UserId,
    val startTime: LocalDateTime,
    val notes: String?,
)

class CreateBookingUseCase(
    private val salonRepository: SalonRepository,
    private val serviceRepository: ServiceRepository,
    private val specialistRepository: SpecialistRepository,
    private val bookingRepository: BookingRepository,
    private val specialistServiceRepository: SpecialistServiceRepository,
) {
    fun execute(command: CreateBookingCommand): Booking {
        val salon = salonRepository.findById(command.salonId)?.takeIf { it.active }
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        salon.requireActivated()
        val service = serviceRepository.findById(command.serviceId)
            ?.takeIf { it.salonId == salon.id && it.active }
            ?: throw ServiceNotFoundException(command.serviceId.value.toString())
        val specialist = specialistRepository.findById(command.specialistId)
            ?.takeIf { it.salonId == salon.id && it.active }
            ?: throw SpecialistNotFoundException(command.specialistId.value.toString())

        val eligibleServiceIds = specialistServiceRepository.findServiceIdsBySpecialistId(specialist.id)
        if (!isSpecialistEligibleForService(eligibleServiceIds, service.id)) {
            throw SpecialistNotEligibleForServiceException(specialist.id.value.toString(), service.id.value.toString())
        }

        val endTime = command.startTime.plusMinutes(service.durationMinutes.toLong())
        val booking = Booking.create(
            salonId = salon.id,
            serviceId = service.id,
            specialistId = specialist.id,
            customerId = command.customerId,
            startTime = command.startTime,
            endTime = endTime,
            notes = command.notes,
        )
        return bookingRepository.reserve(booking)
    }
}

data class ConfirmBookingCommand(val bookingId: BookingId, val callerId: UserId)

class ConfirmBookingUseCase(
    private val bookingRepository: BookingRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: ConfirmBookingCommand): Booking {
        val booking = findBookingOrThrow(bookingRepository, command.bookingId)
        salonPermissionResolver.require(booking.salonId, command.callerId, Permission.MANAGE_BOOKINGS)
        booking.confirm()
        return bookingRepository.save(booking)
    }
}

data class CancelBookingCommand(val bookingId: BookingId, val callerId: UserId)

class CancelBookingUseCase(
    private val bookingRepository: BookingRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: CancelBookingCommand): Booking {
        val booking = findBookingOrThrow(bookingRepository, command.bookingId)
        requireCustomerOrBookingPermission(salonPermissionResolver, booking, command.callerId)
        booking.cancel()
        return bookingRepository.save(booking)
    }
}

data class CompleteBookingCommand(val bookingId: BookingId, val callerId: UserId)

class CompleteBookingUseCase(
    private val bookingRepository: BookingRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: CompleteBookingCommand): Booking {
        val booking = findBookingOrThrow(bookingRepository, command.bookingId)
        salonPermissionResolver.require(booking.salonId, command.callerId, Permission.MANAGE_BOOKINGS)
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
    private val serviceRepository: ServiceRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: RescheduleBookingCommand): Booking {
        val booking = findBookingOrThrow(bookingRepository, command.bookingId)
        requireCustomerOrBookingPermission(salonPermissionResolver, booking, command.callerId)
        val service = serviceRepository.findById(booking.serviceId)
            ?: throw ServiceNotFoundException(booking.serviceId.value.toString())

        val newEndTime = command.newStartTime.plusMinutes(service.durationMinutes.toLong())
        booking.reschedule(command.newStartTime, newEndTime)
        return bookingRepository.reserve(booking, excludeBookingId = booking.id)
    }
}

private fun findBookingOrThrow(bookingRepository: BookingRepository, bookingId: BookingId): Booking =
    bookingRepository.findById(bookingId) ?: throw BookingNotFoundException(bookingId.value.toString())

/** The booking's own customer may always act on it; otherwise the caller needs [Permission.MANAGE_BOOKINGS] at the booking's salon (owner, manager, or receptionist). */
private fun requireCustomerOrBookingPermission(salonPermissionResolver: SalonPermissionResolver, booking: Booking, callerId: UserId) {
    if (booking.customerId == callerId) return
    salonPermissionResolver.require(booking.salonId, callerId, Permission.MANAGE_BOOKINGS)
}
