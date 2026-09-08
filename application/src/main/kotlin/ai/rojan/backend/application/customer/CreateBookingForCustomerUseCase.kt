package ai.rojan.backend.application.customer

import ai.rojan.backend.application.booking.CreateBookingCommand
import ai.rojan.backend.application.booking.CreateBookingUseCase
import ai.rojan.backend.domain.booking.Booking
import ai.rojan.backend.domain.common.CustomerNotFoundException
import ai.rojan.backend.domain.common.CustomerNotLinkedToAccountException
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.customer.CustomerId
import ai.rojan.backend.domain.customer.CustomerRepository
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.ServiceId
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.user.UserId
import java.time.LocalDateTime

data class CreateBookingForCustomerCommand(
    val salonId: SalonId,
    val callerId: UserId,
    val customerId: CustomerId,
    val serviceId: ServiceId,
    val specialistId: SpecialistId,
    val startTime: LocalDateTime,
    val notes: String?,
)

/**
 * ROJAN Reception Booking Flow (Phase 0): the owner-authorized counterpart
 * to `BookingController.create`, which always attributes the new booking to
 * the caller's own identity - the customer self-service path, and it is not
 * modified by this class in any way.
 *
 * Delegates the actual creation to the existing [CreateBookingUseCase] -
 * same reasoning as `GetCustomerBookingsUseCase` reusing [ai.rojan.backend.domain.booking.BookingRepository]
 * rather than duplicating its logic: [CreateBookingUseCase] already owns
 * salon/service/specialist validation and the atomic double-booking
 * conflict check (`BookingRepository.reserve`), and reimplementing that
 * here would risk silently diverging from the self-service path's
 * guarantees. Only the caller-authorization and customer-resolution steps
 * are new.
 *
 * A customer with no linked [ai.rojan.backend.domain.user.UserId] still
 * cannot be booked through this path: [Booking.customerId] remains a real,
 * non-null `UserId` (BACKEND-CRM-CUSTOMER-IDENTITY-001 Phase 1 is additive -
 * it adds [Booking.salonCustomerId] but does not re-anchor the booking).
 * Enabling walk-in (unlinked) booking needs `bookings.customer_id` to become
 * nullable and is a separate, migration-gated phase (see
 * `ROJAN_Reception_Booking_Flow_Plan_v1.md` §4/§7/§8).
 *
 * The already-resolved [ai.rojan.backend.domain.customer.CustomerId] is
 * passed through as [CreateBookingCommand.salonCustomerId], so the delegated
 * [CreateBookingUseCase] does not run a second resolve-or-create.
 */
class CreateBookingForCustomerUseCase(
    private val salonRepository: SalonRepository,
    private val customerRepository: CustomerRepository,
    private val createBookingUseCase: CreateBookingUseCase,
) {
    fun execute(command: CreateBookingForCustomerCommand): Booking {
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        if (salon.ownerId != command.callerId) {
            throw SalonAccessDeniedException(salon.id.value.toString())
        }

        val customer = customerRepository.findById(command.customerId)
            ?.takeIf { it.salonId == command.salonId }
            ?: throw CustomerNotFoundException(command.customerId.value.toString())

        val linkedUserId = customer.userId
            ?: throw CustomerNotLinkedToAccountException(customer.id.value.toString())

        return createBookingUseCase.execute(
            CreateBookingCommand(
                salonId = command.salonId,
                serviceId = command.serviceId,
                specialistId = command.specialistId,
                customerId = linkedUserId,
                startTime = command.startTime,
                notes = command.notes,
                salonCustomerId = customer.id,
            ),
        )
    }
}
