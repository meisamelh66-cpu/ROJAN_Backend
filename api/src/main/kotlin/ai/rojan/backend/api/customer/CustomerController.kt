package ai.rojan.backend.api.customer

import ai.rojan.backend.api.booking.BookingResponse
import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.api.common.PagedResponse
import ai.rojan.backend.api.common.toPagedResponse
import ai.rojan.backend.application.customer.AddCustomerNoteCommand
import ai.rojan.backend.application.customer.AddCustomerNoteUseCase
import ai.rojan.backend.application.customer.AddCustomerTagCommand
import ai.rojan.backend.application.customer.AddCustomerTagUseCase
import ai.rojan.backend.application.customer.CalculateCustomerLifetimeValueUseCase
import ai.rojan.backend.application.customer.CreateCustomerCommand
import ai.rojan.backend.application.customer.CreateCustomerIdentityCommand
import ai.rojan.backend.application.customer.CreateCustomerIdentityUseCase
import ai.rojan.backend.application.customer.CreateCustomerUseCase
import ai.rojan.backend.application.customer.GetCustomerBookingsCommand
import ai.rojan.backend.application.customer.GetCustomerBookingsUseCase
import ai.rojan.backend.application.customer.GetCustomerTimelineCommand
import ai.rojan.backend.application.customer.GetCustomerTimelineUseCase
import ai.rojan.backend.application.customer.RemoveCustomerTagCommand
import ai.rojan.backend.application.customer.RemoveCustomerTagUseCase
import ai.rojan.backend.application.customer.UpdateCustomerCommand
import ai.rojan.backend.application.customer.UpdateCustomerUseCase
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.booking.Booking
import ai.rojan.backend.domain.booking.BookingRepository
import ai.rojan.backend.domain.booking.BookingStatus
import ai.rojan.backend.domain.common.CustomerNotFoundException
import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.SortDirection
import ai.rojan.backend.domain.customer.Customer
import ai.rojan.backend.domain.customer.CustomerId
import ai.rojan.backend.domain.customer.CustomerNoteRepository
import ai.rojan.backend.domain.customer.CustomerRepository
import ai.rojan.backend.domain.customer.CustomerStatus
import ai.rojan.backend.domain.customer.CustomerTagId
import ai.rojan.backend.domain.customer.CustomerTagRepository
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.ServiceRepository
import ai.rojan.backend.domain.user.UserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.util.UUID

/**
 * Every endpoint here requiring [Permission.VIEW_CRM]/[Permission.MANAGE_CRM]
 * is Owner/Manager-only - unlike Booking's "customer or owner" dual-access
 * model, a `User` who happens to also be a [Customer]'s linked account has
 * no read access to their own CRM record via these endpoints (a note like
 * "chargeback risk" must never be customer-visible). See
 * `ROJAN_Customer_CRM_Architecture_Plan_v1.md` §4.
 *
 * [searchIdentity]/[createIdentity] are the exception, per
 * `ROJAN_Reception_Permission_Contract_Update_ADR_v1.md` -
 * [Permission.VIEW_CUSTOMER_IDENTITY]/[Permission.CREATE_CUSTOMER_IDENTITY]
 * additionally admit `RECEPTIONIST`, but only through the structurally
 * narrower [CustomerIdentityResponse]/[CreateCustomerIdentityRequest] -
 * never the full [CustomerResponse]/[CreateCustomerRequest] shapes this
 * class's other endpoints use.
 */
@RestController
@RequestMapping("/api/v1/salons/{salonId}/customers")
@Tag(name = "Customers")
class CustomerController(
    private val customerRepository: CustomerRepository,
    private val customerTagRepository: CustomerTagRepository,
    private val customerNoteRepository: CustomerNoteRepository,
    private val salonRepository: SalonRepository,
    private val bookingRepository: BookingRepository,
    private val serviceRepository: ServiceRepository,
    private val createCustomerUseCase: CreateCustomerUseCase,
    private val createCustomerIdentityUseCase: CreateCustomerIdentityUseCase,
    private val updateCustomerUseCase: UpdateCustomerUseCase,
    private val addCustomerNoteUseCase: AddCustomerNoteUseCase,
    private val addCustomerTagUseCase: AddCustomerTagUseCase,
    private val removeCustomerTagUseCase: RemoveCustomerTagUseCase,
    private val getCustomerTimelineUseCase: GetCustomerTimelineUseCase,
    private val getCustomerBookingsUseCase: GetCustomerBookingsUseCase,
    private val calculateCustomerLifetimeValueUseCase: CalculateCustomerLifetimeValueUseCase,
    private val currentUserResolver: CurrentUserResolver,
    private val salonPermissionResolver: SalonPermissionResolver,
) {

    @GetMapping
    @Operation(summary = "List a salon's customers, paginated and optionally filtered by status/tag/search (owner only)")
    fun list(
        @PathVariable salonId: UUID,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) tag: String?,
        @RequestParam(required = false) search: String?,
        @RequestParam(defaultValue = "ASC") sortDirection: String,
        @AuthenticationPrincipal principal: UserDetails,
    ): PagedResponse<CustomerResponse> {
        val callerId = currentUserResolver.resolve(principal)
        val salon = salonRepository.findById(SalonId(salonId)) ?: throw SalonNotFoundException(salonId.toString())
        salonPermissionResolver.require(salon.id, callerId, Permission.VIEW_CRM)
        val result = customerRepository.findBySalonId(
            salon.id,
            PageRequest(page, size),
            status?.let { CustomerStatus.valueOf(it.uppercase()) },
            tag,
            search,
            SortDirection.valueOf(sortDirection.uppercase()),
        )

        // Production Hardening Phase 1: batched, not per-row - one tags query, one completed-bookings
        // query, and one services-for-the-salon query (reusing the same "load once, associateBy,
        // look up locally" pattern GetDashboardInsightsUseCase already established), instead of the
        // ~20 x (2 + N bookings) queries this page used to issue.
        val pageCustomerIds = result.content.map { it.id }
        val tagsByCustomerId = customerTagRepository.findByCustomerIdIn(pageCustomerIds).groupBy { it.customerId }
        val linkedUserIds = result.content.mapNotNull { it.userId }
        val servicesById = serviceRepository.findBySalonId(salon.id).associateBy { it.id }
        val lifetimeValueByUserId = bookingRepository
            .findCompletedBySalonIdAndCustomerIdIn(salon.id, linkedUserIds)
            .groupBy { it.customerId }
            .mapValues { (_, bookings) -> bookings.sumOf { booking -> servicesById[booking.serviceId]?.price ?: BigDecimal.ZERO } }

        return result.toPagedResponse { customer ->
            customer.toResponse(
                lifetimeValue = customer.userId?.let { lifetimeValueByUserId[it] } ?: BigDecimal.ZERO,
                tags = tagsByCustomerId[customer.id].orEmpty().map { it.label },
            )
        }
    }

    @GetMapping("/identity")
    @Operation(summary = "Search a salon's customers by identity only - name/phone/email, no CRM data (Reception-eligible)")
    fun searchIdentity(
        @PathVariable salonId: UUID,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) search: String?,
        @AuthenticationPrincipal principal: UserDetails,
    ): PagedResponse<CustomerIdentityResponse> {
        val callerId = currentUserResolver.resolve(principal)
        val salon = salonRepository.findById(SalonId(salonId)) ?: throw SalonNotFoundException(salonId.toString())
        salonPermissionResolver.requireAny(salon.id, callerId, Permission.VIEW_CUSTOMER_IDENTITY, Permission.VIEW_CRM)
        val result = customerRepository.findBySalonId(salon.id, PageRequest(page, size), null, null, search, SortDirection.ASC)
        return result.toPagedResponse { it.toIdentityResponse() }
    }

    @GetMapping("/{customerId}")
    @Operation(summary = "Get a customer by id (owner only)")
    fun get(@PathVariable salonId: UUID, @PathVariable customerId: UUID, @AuthenticationPrincipal principal: UserDetails): CustomerResponse {
        val callerId = currentUserResolver.resolve(principal)
        val customer = findCustomerOrThrow(salonId, customerId)
        requireOwner(customer, callerId)
        return customer.toResponse()
    }

    @GetMapping("/{customerId}/timeline")
    @Operation(summary = "Get a customer's merged timeline (notes, tag/status changes, booking events) - paginated (owner only)")
    fun timeline(
        @PathVariable salonId: UUID,
        @PathVariable customerId: UUID,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @AuthenticationPrincipal principal: UserDetails,
    ): PagedResponse<CustomerTimelineEntryResponse> {
        val callerId = currentUserResolver.resolve(principal)
        findCustomerOrThrow(salonId, customerId)
        val result = getCustomerTimelineUseCase.execute(GetCustomerTimelineCommand(CustomerId(customerId), callerId, PageRequest(page, size)))
        return result.toPagedResponse { CustomerTimelineEntryResponse(it.type, it.description, it.occurredAt) }
    }

    @GetMapping("/{customerId}/bookings")
    @Operation(summary = "Get a customer's booking history - empty if the customer has no linked account (owner only)")
    fun bookings(
        @PathVariable salonId: UUID,
        @PathVariable customerId: UUID,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "DESC") sortDirection: String,
        @AuthenticationPrincipal principal: UserDetails,
    ): PagedResponse<BookingResponse> {
        val callerId = currentUserResolver.resolve(principal)
        findCustomerOrThrow(salonId, customerId)
        val result = getCustomerBookingsUseCase.execute(
            GetCustomerBookingsCommand(
                customerId = CustomerId(customerId),
                callerId = callerId,
                pageRequest = PageRequest(page, size),
                statusFilter = status?.let { BookingStatus.valueOf(it.uppercase()) },
                sortDirection = SortDirection.valueOf(sortDirection.uppercase()),
            ),
        )
        return result.toPagedResponse { it.toBookingResponse() }
    }

    @GetMapping("/{customerId}/notes")
    @Operation(summary = "List a customer's notes, oldest first (owner only)")
    fun notes(
        @PathVariable salonId: UUID,
        @PathVariable customerId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ): List<CustomerNoteResponse> {
        val callerId = currentUserResolver.resolve(principal)
        val customer = findCustomerOrThrow(salonId, customerId)
        requireOwner(customer, callerId)
        return customerNoteRepository.findByCustomerId(customer.id)
            .sortedBy { it.createdAt }
            .map { CustomerNoteResponse(it.id.value, it.authorId.value, it.text, it.createdAt) }
    }

    @GetMapping("/{customerId}/tags")
    @Operation(summary = "List a customer's tags with their real ids, oldest first (owner only)")
    fun tags(
        @PathVariable salonId: UUID,
        @PathVariable customerId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ): List<CustomerTagResponse> {
        val callerId = currentUserResolver.resolve(principal)
        val customer = findCustomerOrThrow(salonId, customerId)
        requireOwner(customer, callerId)
        return customerTagRepository.findByCustomerId(customer.id)
            .sortedBy { it.createdAt }
            .map { CustomerTagResponse(it.id.value, it.label, it.createdAt) }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a customer to a salon - a walk-in/manual CRM record with no linked account (owner only)")
    fun create(
        @PathVariable salonId: UUID,
        @Valid @RequestBody request: CreateCustomerRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): CustomerResponse {
        val callerId = currentUserResolver.resolve(principal)
        val customer = createCustomerUseCase.execute(
            CreateCustomerCommand(
                salonId = SalonId(salonId),
                callerId = callerId,
                fullName = request.fullName,
                phoneNumber = request.phoneNumber,
                email = request.email,
                company = request.company,
            ),
        )
        return customer.toResponse()
    }

    @PostMapping("/identity")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a walk-in customer for booking purposes - fullName/phoneNumber/email only, no CRM fields (Reception-eligible)")
    fun createIdentity(
        @PathVariable salonId: UUID,
        @Valid @RequestBody request: CreateCustomerIdentityRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): CustomerIdentityResponse {
        val callerId = currentUserResolver.resolve(principal)
        val customer = createCustomerIdentityUseCase.execute(
            CreateCustomerIdentityCommand(
                salonId = SalonId(salonId),
                callerId = callerId,
                fullName = request.fullName,
                phoneNumber = request.phoneNumber,
                email = request.email,
            ),
        )
        return customer.toIdentityResponse()
    }

    @PatchMapping("/{customerId}")
    @Operation(summary = "Update a customer's profile and/or status - partial update, only supplied fields change (owner only)")
    fun update(
        @PathVariable salonId: UUID,
        @PathVariable customerId: UUID,
        @Valid @RequestBody request: UpdateCustomerRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): CustomerResponse {
        val callerId = currentUserResolver.resolve(principal)
        findCustomerOrThrow(salonId, customerId)
        val updated = updateCustomerUseCase.execute(
            UpdateCustomerCommand(
                customerId = CustomerId(customerId),
                callerId = callerId,
                fullName = request.fullName,
                phoneNumber = request.phoneNumber,
                email = request.email,
                company = request.company,
                status = request.status,
            ),
        )
        return updated.toResponse()
    }

    @PostMapping("/{customerId}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a note to a customer (owner only)")
    fun addNote(
        @PathVariable salonId: UUID,
        @PathVariable customerId: UUID,
        @Valid @RequestBody request: AddCustomerNoteRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): CustomerNoteResponse {
        val callerId = currentUserResolver.resolve(principal)
        findCustomerOrThrow(salonId, customerId)
        val note = addCustomerNoteUseCase.execute(AddCustomerNoteCommand(CustomerId(customerId), callerId, request.text))
        return CustomerNoteResponse(note.id.value, note.authorId.value, note.text, note.createdAt)
    }

    @PostMapping("/{customerId}/tags")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a tag to a customer (owner only)")
    fun addTag(
        @PathVariable salonId: UUID,
        @PathVariable customerId: UUID,
        @Valid @RequestBody request: AddCustomerTagRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): CustomerTagResponse {
        val callerId = currentUserResolver.resolve(principal)
        findCustomerOrThrow(salonId, customerId)
        val tag = addCustomerTagUseCase.execute(AddCustomerTagCommand(CustomerId(customerId), callerId, request.label))
        return CustomerTagResponse(tag.id.value, tag.label, tag.createdAt)
    }

    @DeleteMapping("/{customerId}/tags/{tagId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a tag from a customer (owner only)")
    fun removeTag(
        @PathVariable salonId: UUID,
        @PathVariable customerId: UUID,
        @PathVariable tagId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ) {
        val callerId = currentUserResolver.resolve(principal)
        findCustomerOrThrow(salonId, customerId)
        removeCustomerTagUseCase.execute(RemoveCustomerTagCommand(CustomerId(customerId), CustomerTagId(tagId), callerId))
    }

    private fun findCustomerOrThrow(salonId: UUID, customerId: UUID): Customer =
        customerRepository.findById(CustomerId(customerId))
            ?.takeIf { it.salonId == SalonId(salonId) }
            ?: throw CustomerNotFoundException(customerId.toString())

    /**
     * Only needed by [get] - every other endpoint delegates to a use case
     * that already performs this exact check itself (see each use case's
     * own doc comment), so re-checking here would be redundant there. [get]
     * is the one pure-read path with no use case underneath it, so without
     * this it would leak any salon's customer data to any authenticated
     * caller who knows a salonId/customerId pair - see
     * `ROJAN_Customer_CRM_Architecture_Plan_v1.md` §4.
     */
    private fun requireOwner(customer: Customer, callerId: UserId) {
        val salon = salonRepository.findById(customer.salonId) ?: throw SalonNotFoundException(customer.salonId.value.toString())
        salonPermissionResolver.require(salon.id, callerId, Permission.VIEW_CRM)
    }

    /** Single-customer path (get/create/update/etc.) - O(1) queries already, untouched by the list()-only batching below. */
    private fun Customer.toResponse(): CustomerResponse =
        toResponse(
            lifetimeValue = calculateCustomerLifetimeValueUseCase.execute(this),
            tags = customerTagRepository.findByCustomerId(id).map { it.label },
        )

    private fun Customer.toResponse(lifetimeValue: BigDecimal, tags: List<String>): CustomerResponse = CustomerResponse(
        id = id.value,
        salonId = salonId.value,
        userId = userId?.value,
        fullName = fullName,
        phoneNumber = phoneNumber?.value,
        email = email?.value,
        company = company,
        status = status,
        lifetimeValue = lifetimeValue,
        tags = tags,
        active = active,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun Customer.toIdentityResponse(): CustomerIdentityResponse = CustomerIdentityResponse(
        id = id.value,
        salonId = salonId.value,
        fullName = fullName,
        phoneNumber = phoneNumber?.value,
        email = email?.value,
        active = active,
    )

    private fun Booking.toBookingResponse() = BookingResponse(
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
