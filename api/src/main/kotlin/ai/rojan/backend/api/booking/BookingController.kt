package ai.rojan.backend.api.booking

import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.application.booking.CancelBookingCommand
import ai.rojan.backend.application.booking.CancelBookingUseCase
import ai.rojan.backend.application.booking.CompleteBookingCommand
import ai.rojan.backend.application.booking.CompleteBookingUseCase
import ai.rojan.backend.application.booking.ConfirmBookingCommand
import ai.rojan.backend.application.booking.ConfirmBookingUseCase
import ai.rojan.backend.application.booking.CreateBookingCommand
import ai.rojan.backend.application.booking.CreateBookingUseCase
import ai.rojan.backend.application.booking.RescheduleBookingCommand
import ai.rojan.backend.application.booking.RescheduleBookingUseCase
import ai.rojan.backend.domain.booking.Booking
import ai.rojan.backend.domain.booking.BookingId
import ai.rojan.backend.domain.booking.BookingRepository
import ai.rojan.backend.domain.common.BookingAccessDeniedException
import ai.rojan.backend.domain.common.BookingNotFoundException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.ServiceId
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.user.UserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/bookings")
@Tag(name = "Bookings")
class BookingController(
    private val bookingRepository: BookingRepository,
    private val salonRepository: SalonRepository,
    private val createBookingUseCase: CreateBookingUseCase,
    private val confirmBookingUseCase: ConfirmBookingUseCase,
    private val cancelBookingUseCase: CancelBookingUseCase,
    private val completeBookingUseCase: CompleteBookingUseCase,
    private val rescheduleBookingUseCase: RescheduleBookingUseCase,
    private val currentUserResolver: CurrentUserResolver,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a booking as the authenticated customer")
    fun create(
        @Valid @RequestBody request: CreateBookingRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): BookingResponse {
        val customerId = currentUserResolver.resolve(principal)
        val booking = createBookingUseCase.execute(
            CreateBookingCommand(
                salonId = SalonId(request.salonId),
                serviceId = ServiceId(request.serviceId),
                specialistId = SpecialistId(request.specialistId),
                customerId = customerId,
                startTime = request.startTime,
                notes = request.notes,
            ),
        )
        return booking.toResponse()
    }

    @GetMapping("/mine")
    @Operation(summary = "List the authenticated customer's bookings")
    fun mine(@AuthenticationPrincipal principal: UserDetails): List<BookingResponse> {
        val customerId = currentUserResolver.resolve(principal)
        return bookingRepository.findByCustomerId(customerId).map { it.toResponse() }
    }

    @GetMapping("/{bookingId}")
    @Operation(summary = "Get a booking (its customer or the salon owner only)")
    fun get(@PathVariable bookingId: UUID, @AuthenticationPrincipal principal: UserDetails): BookingResponse {
        val callerId = currentUserResolver.resolve(principal)
        val booking = findBookingOrThrow(bookingId)
        requireCustomerOrOwner(booking, callerId)
        return booking.toResponse()
    }

    @PatchMapping("/{bookingId}/confirm")
    @Operation(summary = "Confirm a pending booking (salon owner only)")
    fun confirm(@PathVariable bookingId: UUID, @AuthenticationPrincipal principal: UserDetails): BookingResponse {
        val callerId = currentUserResolver.resolve(principal)
        return confirmBookingUseCase.execute(ConfirmBookingCommand(BookingId(bookingId), callerId)).toResponse()
    }

    @PatchMapping("/{bookingId}/cancel")
    @Operation(summary = "Cancel a booking (its customer or the salon owner)")
    fun cancel(@PathVariable bookingId: UUID, @AuthenticationPrincipal principal: UserDetails): BookingResponse {
        val callerId = currentUserResolver.resolve(principal)
        return cancelBookingUseCase.execute(CancelBookingCommand(BookingId(bookingId), callerId)).toResponse()
    }

    @PatchMapping("/{bookingId}/complete")
    @Operation(summary = "Mark a confirmed booking completed (salon owner only)")
    fun complete(@PathVariable bookingId: UUID, @AuthenticationPrincipal principal: UserDetails): BookingResponse {
        val callerId = currentUserResolver.resolve(principal)
        return completeBookingUseCase.execute(CompleteBookingCommand(BookingId(bookingId), callerId)).toResponse()
    }

    @PutMapping("/{bookingId}/reschedule")
    @Operation(summary = "Reschedule a booking to a new start time (its customer or the salon owner)")
    fun reschedule(
        @PathVariable bookingId: UUID,
        @Valid @RequestBody request: RescheduleBookingRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): BookingResponse {
        val callerId = currentUserResolver.resolve(principal)
        return rescheduleBookingUseCase.execute(
            RescheduleBookingCommand(BookingId(bookingId), callerId, request.newStartTime),
        ).toResponse()
    }

    private fun findBookingOrThrow(bookingId: UUID): Booking =
        bookingRepository.findById(BookingId(bookingId)) ?: throw BookingNotFoundException(bookingId.toString())

    private fun requireCustomerOrOwner(booking: Booking, callerId: UserId) {
        if (booking.customerId == callerId) return
        val salon = salonRepository.findById(booking.salonId)
            ?: throw SalonNotFoundException(booking.salonId.value.toString())
        if (salon.ownerId != callerId) throw BookingAccessDeniedException(booking.id.value.toString())
    }

    private fun Booking.toResponse() = BookingResponse(
        id = id.value,
        salonId = salonId.value,
        serviceId = serviceId.value,
        specialistId = specialistId.value,
        customerId = customerId.value,
        startTime = startTime,
        endTime = endTime,
        status = status,
        notes = notes,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
