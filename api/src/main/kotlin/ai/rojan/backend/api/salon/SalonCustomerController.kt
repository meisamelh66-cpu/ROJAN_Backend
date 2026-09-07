package ai.rojan.backend.api.salon

import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.common.ApiError
import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.domain.booking.BookingRepository
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Manager Booking Creation Integrity follow-up. Lets a salon owner search
 * the customers who have actually booked with their salon, so a
 * receptionist/manager can create a booking on an existing customer's
 * behalf without either of the two things the approved plan explicitly
 * ruled out: a "browse every user in the system" capability (a real
 * privacy concern — customer search is scoped to this salon's own
 * history, never global), or a new customer-identity concept for a
 * walk-in with no account (out of scope; this only ever returns real
 * [User] accounts).
 *
 * "Belongs to this salon" is derived from [BookingRepository] — there is
 * no separate salon-customer-roster entity, and adding one would be new
 * scope beyond what closing this gap actually requires. Regardless of
 * booking status (a cancelled booking still means the person is a real,
 * known customer of this salon). Not paginated, same reasoning as
 * [SalonController.mine]: a single salon's distinct-customer count is
 * realistically bounded for this milestone.
 */
@RestController
@RequestMapping("/api/v1/salons/{salonId}/customers")
@Tag(name = "Salon Customers")
class SalonCustomerController(
    private val salonRepository: SalonRepository,
    private val bookingRepository: BookingRepository,
    private val userRepository: UserRepository,
    private val currentUserResolver: CurrentUserResolver,
) {

    @GetMapping
    @Operation(
        summary = "Search customers who have booked with this salon (owner only)",
        description = "`query`, when present, matches a case-insensitive substring of the customer's full name or email.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Matching customers, sorted by name"),
        ApiResponse(
            responseCode = "403",
            description = "Caller is not this salon's owner",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "No salon with this id",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
    )
    fun search(
        @PathVariable salonId: UUID,
        @RequestParam(required = false) query: String?,
        @AuthenticationPrincipal principal: UserDetails,
    ): List<UserResponse> {
        val callerId = currentUserResolver.resolve(principal)
        val salon = salonRepository.findById(SalonId(salonId)) ?: throw SalonNotFoundException(salonId.toString())
        if (salon.ownerId != callerId) throw SalonAccessDeniedException(salon.id.value.toString())

        val normalizedQuery = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return bookingRepository.findDistinctCustomerIdsBySalonId(salon.id)
            .mapNotNull { userRepository.findById(it) }
            .filter { normalizedQuery == null || it.matches(normalizedQuery) }
            .sortedBy { it.fullName }
            .map { it.toResponse() }
    }

    private fun User.matches(normalizedQuery: String): Boolean =
        fullName.lowercase().contains(normalizedQuery) || email.value.lowercase().contains(normalizedQuery)

    private fun User.toResponse() = UserResponse(id = id.value, email = email.value, fullName = fullName, role = role)
}
