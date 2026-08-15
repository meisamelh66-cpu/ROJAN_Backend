package ai.rojan.backend.api.booking

import ai.rojan.backend.api.common.ApiError
import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.api.common.PagedResponse
import ai.rojan.backend.api.common.toPagedResponse
import ai.rojan.backend.application.customer.CreateBookingForCustomerCommand
import ai.rojan.backend.application.customer.CreateBookingForCustomerUseCase
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.booking.Booking
import ai.rojan.backend.domain.booking.BookingRepository
import ai.rojan.backend.domain.booking.BookingStatus
import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.common.SortDirection
import ai.rojan.backend.domain.customer.CustomerId
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.ServiceId
import ai.rojan.backend.domain.salon.SpecialistId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/salons/{salonId}/bookings")
@Tag(name = "Bookings")
class SalonBookingController(
    private val bookingRepository: BookingRepository,
    private val salonRepository: SalonRepository,
    private val createBookingForCustomerUseCase: CreateBookingForCustomerUseCase,
    private val currentUserResolver: CurrentUserResolver,
    private val salonPermissionResolver: SalonPermissionResolver,
) {

    @GetMapping
    @Operation(summary = "List all bookings for a salon, paginated and optionally filtered by status (owner, manager, or receptionist)")
    fun list(
        @PathVariable salonId: UUID,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "DESC") sortDirection: String,
        @AuthenticationPrincipal principal: UserDetails,
    ): PagedResponse<BookingResponse> {
        val callerId = currentUserResolver.resolve(principal)
        val salon = salonRepository.findById(SalonId(salonId)) ?: throw SalonNotFoundException(salonId.toString())
        salonPermissionResolver.require(salon.id, callerId, Permission.MANAGE_BOOKINGS)
        val result = bookingRepository.findBySalonId(
            salon.id,
            PageRequest(page, size),
            status?.let { BookingStatus.valueOf(it.uppercase()) },
            SortDirection.valueOf(sortDirection.uppercase()),
        )
        return result.toPagedResponse { it.toResponse() }
    }

    @PostMapping
    @Operation(
        summary = "Create a booking on behalf of a customer - Reception/owner only",
        description = "The owner-authorized counterpart to POST /api/v1/bookings, which always books the caller " +
            "themself and is unchanged by this endpoint. customerId is a Customer CRM id, not a User id; the " +
            "target customer must belong to this salon and already be linked to an account.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Booking created (status PENDING)"),
        ApiResponse(
            responseCode = "403",
            description = "Caller is not this salon's owner",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "The salon, customer, service, or specialist does not exist (or the customer belongs to a different salon)",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "The customer has no linked account yet, or the specialist already has an overlapping active booking",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
    )
    fun createForCustomer(
        @PathVariable salonId: UUID,
        @Valid @RequestBody request: CreateBookingForCustomerRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): ResponseEntity<BookingResponse> {
        val callerId = currentUserResolver.resolve(principal)
        val booking = createBookingForCustomerUseCase.execute(
            CreateBookingForCustomerCommand(
                salonId = SalonId(salonId),
                callerId = callerId,
                customerId = CustomerId(request.customerId),
                serviceId = ServiceId(request.serviceId),
                specialistId = SpecialistId(request.specialistId),
                startTime = request.startTime,
                notes = request.notes,
            ),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(booking.toResponse())
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
