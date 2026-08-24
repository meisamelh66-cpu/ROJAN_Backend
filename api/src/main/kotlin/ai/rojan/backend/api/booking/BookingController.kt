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
import ai.rojan.backend.application.customer.EnsureCustomerAssociationCommand
import ai.rojan.backend.application.customer.EnsureCustomerAssociationUseCase
import ai.rojan.backend.application.port.IdempotencyLookup
import ai.rojan.backend.application.port.IdempotencyPort
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.booking.Booking
import ai.rojan.backend.domain.booking.BookingId
import ai.rojan.backend.domain.booking.BookingRepository
import ai.rojan.backend.domain.booking.BookingStatus
import ai.rojan.backend.domain.common.BookingNotFoundException
import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.SortDirection
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.ServiceId
import ai.rojan.backend.domain.salon.SpecialistId
import ai.rojan.backend.domain.user.UserId
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
    private val createBookingUseCase: CreateBookingUseCase,
    private val confirmBookingUseCase: ConfirmBookingUseCase,
    private val cancelBookingUseCase: CancelBookingUseCase,
    private val completeBookingUseCase: CompleteBookingUseCase,
    private val rescheduleBookingUseCase: RescheduleBookingUseCase,
    private val currentUserResolver: CurrentUserResolver,
    private val idempotencyPort: IdempotencyPort,
    private val salonPermissionResolver: SalonPermissionResolver,
    private val ensureCustomerAssociationUseCase: EnsureCustomerAssociationUseCase,
) {

    @PostMapping
    @Operation(
        summary = "Create a booking as the authenticated customer",
        description = "Supports an optional `Idempotency-Key` request header: replaying the same key with an " +
            "identical body returns the original 201 response instead of creating a duplicate booking; replaying " +
            "it with a different body returns 409.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Booking created (status PENDING)"),
        ApiResponse(
            responseCode = "409",
            description = "The specialist already has an active booking overlapping this time, or the " +
                "Idempotency-Key was reused with a different request body",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "The salon, service, or specialist does not exist",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
    )
    fun create(
        @Valid @RequestBody request: CreateBookingRequest,
        @RequestHeader(IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKey: String?,
        @AuthenticationPrincipal principal: UserDetails,
    ): ResponseEntity<BookingResponse> {
        val customerId = currentUserResolver.resolve(principal)
        val fingerprint = idempotencyKey?.let { fingerprintOf(customerId, request) }

        if (idempotencyKey != null) {
            when (val lookup = idempotencyPort.lookup(idempotencyKey, fingerprint!!, BookingResponse::class.java)) {
                is IdempotencyLookup.Replay -> return ResponseEntity.status(lookup.statusCode).body(lookup.body)
                IdempotencyLookup.Conflict -> throw IdempotencyKeyConflictException(idempotencyKey)
                IdempotencyLookup.NotFound -> Unit
            }
        }

        // Self-service booking never used to leave any CRM trace of the customer at this salon -
        // reception-created bookings always went through a linked Customer, but the QR/self-service
        // journey had no equivalent. Idempotent find-or-create - see the use case's own doc comment.
        ensureCustomerAssociationUseCase.execute(EnsureCustomerAssociationCommand(SalonId(request.salonId), customerId))

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
        val response = booking.toResponse()
        if (idempotencyKey != null) {
            idempotencyPort.store(idempotencyKey, fingerprint!!, HttpStatus.CREATED.value(), response)
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
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

    /**
     * Idempotency fingerprint for [confirm]/[cancel]/[complete]/[reschedule]. Unlike `create`'s
     * body-hash fingerprint above, three of these four take no request body at all - but the
     * `idempotency_keys` table's key column is globally unique across every endpoint, not scoped
     * per action or per booking, so [action] must be an explicit component here, not left implicit.
     * Without it, confirming then cancelling the same booking under one reused key would hash
     * identically on (caller, booking) alone, and the cancel would be misread as a replay of the
     * confirm - returning the cached confirm response and never executing the cancellation.
     * [extra] carries reschedule's body field; the other three pass none.
     */
    private fun fingerprintOf(action: String, callerId: UserId, bookingId: UUID, vararg extra: String): String {
        val raw = (listOf(action, callerId.value.toString(), bookingId.toString()) + extra).joinToString("|")
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
    @Operation(
        summary = "Confirm a pending booking (salon owner only)",
        description = "Supports an optional `Idempotency-Key` request header, same contract as `create`: " +
            "replaying the same key returns the original response instead of re-confirming; reusing it for a " +
            "different booking or a different action (e.g. cancel) returns 409.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Booking confirmed"),
        ApiResponse(
            responseCode = "409",
            description = "The booking is not currently PENDING, or the Idempotency-Key was reused for a different booking/action",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(responseCode = "403", description = "Caller is not the salon owner"),
    )
    fun confirm(
        @PathVariable bookingId: UUID,
        @RequestHeader(IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKey: String?,
        @AuthenticationPrincipal principal: UserDetails,
    ): ResponseEntity<BookingResponse> {
        val callerId = currentUserResolver.resolve(principal)
        return withIdempotency(idempotencyKey, "confirm", callerId, bookingId) {
            confirmBookingUseCase.execute(ConfirmBookingCommand(BookingId(bookingId), callerId)).toResponse()
        }
    }

    @PatchMapping("/{bookingId}/cancel")
    @Operation(
        summary = "Cancel a booking (its customer or the salon owner)",
        description = "Supports an optional `Idempotency-Key` request header, same contract as `create`: " +
            "replaying the same key returns the original response instead of re-cancelling; reusing it for a " +
            "different booking or a different action (e.g. confirm) returns 409.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Booking cancelled"),
        ApiResponse(
            responseCode = "409",
            description = "The booking is already CANCELLED or COMPLETED, or the Idempotency-Key was reused for a different booking/action",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(responseCode = "403", description = "Caller is neither the customer nor the salon owner"),
    )
    fun cancel(
        @PathVariable bookingId: UUID,
        @RequestHeader(IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKey: String?,
        @AuthenticationPrincipal principal: UserDetails,
    ): ResponseEntity<BookingResponse> {
        val callerId = currentUserResolver.resolve(principal)
        return withIdempotency(idempotencyKey, "cancel", callerId, bookingId) {
            cancelBookingUseCase.execute(CancelBookingCommand(BookingId(bookingId), callerId)).toResponse()
        }
    }

    @PatchMapping("/{bookingId}/complete")
    @Operation(
        summary = "Mark a confirmed booking completed (salon owner only)",
        description = "Supports an optional `Idempotency-Key` request header, same contract as `create`: " +
            "replaying the same key returns the original response instead of re-completing; reusing it for a " +
            "different booking or a different action returns 409.",
    )
    fun complete(
        @PathVariable bookingId: UUID,
        @RequestHeader(IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKey: String?,
        @AuthenticationPrincipal principal: UserDetails,
    ): ResponseEntity<BookingResponse> {
        val callerId = currentUserResolver.resolve(principal)
        return withIdempotency(idempotencyKey, "complete", callerId, bookingId) {
            completeBookingUseCase.execute(CompleteBookingCommand(BookingId(bookingId), callerId)).toResponse()
        }
    }

    @PutMapping("/{bookingId}/reschedule")
    @Operation(
        summary = "Reschedule a booking to a new start time (its customer or the salon owner)",
        description = "Supports an optional `Idempotency-Key` request header, same contract as `create`: " +
            "replaying the same key with an identical body returns the original response instead of moving the " +
            "booking again; replaying it with a different body, a different booking, or a different action returns 409.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Booking moved to the new time"),
        ApiResponse(
            responseCode = "409",
            description = "The specialist already has another active booking overlapping the new time, or the " +
                "Idempotency-Key was reused with a different request body/booking/action",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
    )
    fun reschedule(
        @PathVariable bookingId: UUID,
        @Valid @RequestBody request: RescheduleBookingRequest,
        @RequestHeader(IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKey: String?,
        @AuthenticationPrincipal principal: UserDetails,
    ): ResponseEntity<BookingResponse> {
        val callerId = currentUserResolver.resolve(principal)
        return withIdempotency(idempotencyKey, "reschedule", callerId, bookingId, request.newStartTime.toString()) {
            rescheduleBookingUseCase.execute(
                RescheduleBookingCommand(BookingId(bookingId), callerId, request.newStartTime),
            ).toResponse()
        }
    }

    /**
     * Shared idempotency wrapper for [confirm]/[cancel]/[complete]/[reschedule] - same
     * lookup-or-execute-then-store shape [create] already has inline, extracted here since four
     * call sites share it. [action]/[extra] feed [fingerprintOf]'s operation-aware fingerprint;
     * see that function's own doc comment for why [action] must not be omitted.
     */
    private fun withIdempotency(
        idempotencyKey: String?,
        action: String,
        callerId: UserId,
        bookingId: UUID,
        vararg extra: String,
        execute: () -> BookingResponse,
    ): ResponseEntity<BookingResponse> {
        if (idempotencyKey == null) {
            return ResponseEntity.ok(execute())
        }

        val fingerprint = fingerprintOf(action, callerId, bookingId, *extra)
        when (val lookup = idempotencyPort.lookup(idempotencyKey, fingerprint, BookingResponse::class.java)) {
            is IdempotencyLookup.Replay -> return ResponseEntity.status(lookup.statusCode).body(lookup.body)
            IdempotencyLookup.Conflict -> throw IdempotencyKeyConflictException(idempotencyKey)
            IdempotencyLookup.NotFound -> Unit
        }

        val response = execute()
        idempotencyPort.store(idempotencyKey, fingerprint, HttpStatus.OK.value(), response)
        return ResponseEntity.ok(response)
    }

    private fun findBookingOrThrow(bookingId: UUID): Booking =
        bookingRepository.findById(BookingId(bookingId)) ?: throw BookingNotFoundException(bookingId.toString())

    private fun requireCustomerOrOwner(booking: Booking, callerId: UserId) {
        if (booking.customerId == callerId) return
        salonPermissionResolver.require(booking.salonId, callerId, Permission.MANAGE_BOOKINGS)
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
