package ai.rojan.backend.api.booking

import ai.rojan.backend.api.common.ApiError
import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.api.common.IdempotencyKeyConflictException
import ai.rojan.backend.api.common.PagedResponse
import ai.rojan.backend.api.common.toPagedResponse
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
import ai.rojan.backend.application.customer.ResolveOrCreateSalonCustomerCommand
import ai.rojan.backend.application.customer.ResolveOrCreateSalonCustomerUseCase
import ai.rojan.backend.application.port.IdempotencyLookup
import ai.rojan.backend.application.port.IdempotencyPort
import ai.rojan.backend.domain.booking.Booking
import ai.rojan.backend.domain.booking.BookingId
import ai.rojan.backend.domain.booking.BookingRepository
import ai.rojan.backend.domain.booking.BookingStatus
import ai.rojan.backend.domain.common.BookingAccessDeniedException
import ai.rojan.backend.domain.common.BookingNotFoundException
import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.common.SortDirection
import ai.rojan.backend.domain.common.UserNotFoundException
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.ServiceId
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository
import ai.rojan.backend.domain.user.UserRole
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
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.util.UUID

private const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"

@RestController
@RequestMapping("/api/v1/bookings")
@Tag(name = "Bookings")
class BookingController(
    private val bookingRepository: BookingRepository,
    private val salonRepository: SalonRepository,
    private val userRepository: UserRepository,
    private val createBookingUseCase: CreateBookingUseCase,
    private val resolveOrCreateSalonCustomerUseCase: ResolveOrCreateSalonCustomerUseCase,
    private val confirmBookingUseCase: ConfirmBookingUseCase,
    private val cancelBookingUseCase: CancelBookingUseCase,
    private val completeBookingUseCase: CompleteBookingUseCase,
    private val rescheduleBookingUseCase: RescheduleBookingUseCase,
    private val currentUserResolver: CurrentUserResolver,
    private val idempotencyPort: IdempotencyPort,
) {

    @PostMapping
    @Operation(
        summary = "Create a booking as the authenticated customer, or on a customer's behalf as their salon's owner",
        description = "Supports an optional `Idempotency-Key` request header: replaying the same key with an " +
            "identical body returns the original 201 response instead of creating a duplicate booking; replaying " +
            "it with a different body returns 409. When `customerId` is supplied, the caller must own " +
            "`salonId` and `customerId` must be a real, existing customer account - see `CreateBookingRequest.customerId`.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Booking created (status PENDING)"),
        ApiResponse(
            responseCode = "403",
            description = "`customerId` was supplied but the caller does not own `salonId`",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "The specialist already has an active booking overlapping this time, or the " +
                "Idempotency-Key was reused with a different request body",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "The salon, service, specialist, or (when supplied) customerId does not exist",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
    )
    fun create(
        @Valid @RequestBody request: CreateBookingRequest,
        @RequestHeader(IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKey: String?,
        @AuthenticationPrincipal principal: UserDetails,
    ): ResponseEntity<BookingResponse> {
        val callerId = currentUserResolver.resolve(principal)
        val customerId = resolveBookingCustomerId(request, callerId)
        val fingerprint = idempotencyKey?.let { fingerprintOf(customerId, request) }

        if (idempotencyKey != null) {
            when (val lookup = idempotencyPort.lookup(idempotencyKey, fingerprint!!, BookingResponse::class.java)) {
                is IdempotencyLookup.Replay -> return ResponseEntity.status(lookup.statusCode).body(lookup.body)
                IdempotencyLookup.Conflict -> throw IdempotencyKeyConflictException(idempotencyKey)
                IdempotencyLookup.NotFound -> Unit
            }
        }

        // BACKEND-CRM-CUSTOMER-IDENTITY-001: anchor the booking to the
        // salon's CRM record for this account, creating a linked one from
        // the account's profile if the salon has none yet. Done after the
        // idempotency replay short-circuit so a replay never creates a row.
        val salonCustomerId = resolveOrCreateSalonCustomerUseCase.execute(
            ResolveOrCreateSalonCustomerCommand(SalonId(request.salonId), customerId),
        ).id

        val booking = createBookingUseCase.execute(
            CreateBookingCommand(
                salonId = SalonId(request.salonId),
                serviceId = ServiceId(request.serviceId),
                specialistId = SpecialistId(request.specialistId),
                customerId = customerId,
                startTime = request.startTime,
                notes = request.notes,
                salonCustomerId = salonCustomerId,
            ),
        )
        val response = booking.toResponse()
        if (idempotencyKey != null) {
            idempotencyPort.store(idempotencyKey, fingerprint!!, HttpStatus.CREATED.value(), response)
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    /**
     * Manager Booking Creation Integrity follow-up. `request.customerId`
     * absent (the normal case): the booking is for the caller themselves,
     * exactly as before this change - [callerId] is returned unchanged.
     * Present: the caller must own `request.salonId` (never let an
     * arbitrary authenticated account attribute a booking to someone
     * else), and the given id must resolve to a real, existing `CUSTOMER`
     * account (never silently fall back to the caller's own id, and never
     * accept a non-customer account here).
     */
    private fun resolveBookingCustomerId(request: CreateBookingRequest, callerId: UserId): UserId {
        val requestedCustomerId = request.customerId ?: return callerId

        val salon = salonRepository.findById(SalonId(request.salonId))
            ?: throw SalonNotFoundException(request.salonId.toString())
        if (salon.ownerId != callerId) throw SalonAccessDeniedException(salon.id.value.toString())

        val customer = userRepository.findById(UserId(requestedCustomerId))
            ?.takeIf { it.role == UserRole.CUSTOMER }
            ?: throw UserNotFoundException(requestedCustomerId.toString())
        return customer.id
    }

    @GetMapping("/mine")
    @Operation(summary = "List the authenticated customer's bookings, paginated and optionally filtered by status")
    fun mine(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "DESC") sortDirection: String,
        @AuthenticationPrincipal principal: UserDetails,
    ): PagedResponse<BookingResponse> {
        val customerId = currentUserResolver.resolve(principal)
        val result = bookingRepository.findByCustomerId(
            customerId,
            PageRequest(page, size),
            status?.let { BookingStatus.valueOf(it.uppercase()) },
            SortDirection.valueOf(sortDirection.uppercase()),
        )
        return result.toPagedResponse { it.toResponse() }
    }

    private fun fingerprintOf(customerId: UserId, request: CreateBookingRequest): String {
        val raw = listOf(
            customerId.value, request.salonId, request.serviceId, request.specialistId, request.startTime, request.notes.orEmpty(),
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
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
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Booking confirmed"),
        ApiResponse(
            responseCode = "409",
            description = "The booking is not currently PENDING",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(responseCode = "403", description = "Caller is not the salon owner"),
    )
    fun confirm(@PathVariable bookingId: UUID, @AuthenticationPrincipal principal: UserDetails): BookingResponse {
        val callerId = currentUserResolver.resolve(principal)
        return confirmBookingUseCase.execute(ConfirmBookingCommand(BookingId(bookingId), callerId)).toResponse()
    }

    @PatchMapping("/{bookingId}/cancel")
    @Operation(summary = "Cancel a booking (its customer or the salon owner)")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Booking cancelled"),
        ApiResponse(
            responseCode = "409",
            description = "The booking is already CANCELLED or COMPLETED",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(responseCode = "403", description = "Caller is neither the customer nor the salon owner"),
    )
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
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Booking moved to the new time"),
        ApiResponse(
            responseCode = "409",
            description = "The specialist already has another active booking overlapping the new time",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
    )
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
